package edu.duke.cs.osprey.energy.forcefield;

import java.io.IOException;
import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.jogamp.opencl.CLCommandQueue;
import com.jogamp.opencl.CLEvent;
import com.jogamp.opencl.CLEvent.ProfilingCommand;
import com.jogamp.opencl.CLEventList;
import com.jogamp.opencl.CLException;

import edu.duke.cs.osprey.dof.DegreeOfFreedom;
import edu.duke.cs.osprey.energy.EnergyFunction;
import edu.duke.cs.osprey.energy.forcefield.ForcefieldInteractions.AtomGroup;
import edu.duke.cs.osprey.gpu.GpuQueuePool;
import edu.duke.cs.osprey.gpu.kernels.ForceFieldKernel;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.Residue;
import edu.duke.cs.osprey.tools.TimeFormatter;

public class GpuForcefieldEnergy implements EnergyFunction.DecomposableByDof, EnergyFunction.NeedsCleanup {
	
	private static final long serialVersionUID = -9142317985561910731L;
	
	private class KernelBuilder {
		
		private ForceFieldKernel.Bound kernel;
		
		public KernelBuilder() {
			kernel = null;
		}
		
		public ForceFieldKernel.Bound get() {
			
			// do we need to rebuild the forcefield?
			// NOTE: make sure we check for chemical changes even if the kernel is null
			// so we acknowledge any chemical changes that may have happened 
			if (hasChemicalChanges() || kernel == null) {
				
				// cleanup any old kernel if needed
				if (kernel != null) {
					kernel.cleanup();
					kernel = null;
				}
				
				try {
					
					// prep the kernel, upload precomputed data
					ffenergy = new BigForcefieldEnergy(ffparams, interactions, true);
					kernel = new ForceFieldKernel().bind(queue);
					kernel.setForcefield(ffenergy);
					kernel.uploadStaticAsync();
					
				} catch (IOException ex) {
					
					// if we can't find the gpu kernel source, that's something a programmer needs to fix
					throw new Error("can't initialize gpu kernel", ex);
				}
			}
			
			return kernel;
		}
		
		private boolean hasChemicalChanges() {
			
			// look for residue template changes so we can rebuild the forcefield
			boolean hasChanges = false;
			for (AtomGroup[] pair : interactions) {
				for (AtomGroup group : pair) {
					if (group.hasChemicalChange()) {
						hasChanges = true;
					}
					group.ackChemicalChange();
				}
			}
			return hasChanges;
		}
		
		public void cleanup() {
			if (kernel != null) {
				kernel.cleanup();
			}
		}
	}
	
	private ForcefieldParams ffparams;
	private ForcefieldInteractions interactions;
	private BigForcefieldEnergy ffenergy;
	private KernelBuilder kernelBuilder;
	private GpuQueuePool queuePool;
	private CLCommandQueue queue;
	
	private GpuForcefieldEnergy(ForcefieldParams ffparams, ForcefieldInteractions interactions) {
		this.ffparams = ffparams;
		this.interactions = interactions;
		
		ffenergy = null;
		kernelBuilder = new KernelBuilder();
	}
	
	public GpuForcefieldEnergy(ForcefieldParams ffparams, ForcefieldInteractions interactions, GpuQueuePool queuePool) {
		this(ffparams, interactions);
		
		this.queuePool = queuePool;
		queue = queuePool.checkout();
	}
	
	private GpuForcefieldEnergy(GpuForcefieldEnergy parent, ForcefieldInteractions interactions) {
		this(parent.ffparams, interactions);
		
		this.queuePool = null;
		queue = parent.queue;
	}
	
	public BigForcefieldEnergy getForcefieldEnergy() {
		return ffenergy;
	}
	
	public ForceFieldKernel.Bound getKernel() {
		return kernelBuilder.get();
	}
	
	public void startProfile() {
		kernelBuilder.get().initProfilingEvents();
	}
	
	public String dumpProfile() {
		ForceFieldKernel.Bound kernel = kernelBuilder.get();
		StringBuilder buf = new StringBuilder();
		CLEventList events = kernel.getProfilingEvents();
		for (CLEvent event : events) {
			try {
				long startNs = event.getProfilingInfo(ProfilingCommand.START);
				long endNs = event.getProfilingInfo(ProfilingCommand.END);
				buf.append(String.format(
					"%s %s\n",
					event.getType(),
					TimeFormatter.format(endNs - startNs, TimeUnit.MICROSECONDS)
				));
			} catch (CLException.CLProfilingInfoNotAvailableException ex) {
				buf.append(String.format("%s (unknown timing)\n", event.getType()));
			}
		}
		kernel.clearProfilingEvents();
		return buf.toString();
	}
	
	@Override
	public double getEnergy() {
		
		ForceFieldKernel.Bound kernel = kernelBuilder.get();
		
		// upload coords
		ffenergy.updateCoords();
		kernel.uploadCoordsAsync();
		
		// run the kernel
		kernel.runAsync();
		
		// read the results
		return sumEnergy(kernel.downloadEnergiesSync());
	}
	
	private double sumEnergy(DoubleBuffer buf) {
		
		// do the last bit of the energy sum on the cpu
		// add one element per work group on the gpu
		// typically, it's a factor of groupSize less than the number of atom pairs
		buf.rewind();
		double energy = ffenergy.getInternalSolvationEnergy();
		while (buf.hasRemaining()) {
			energy += buf.get();
		}
		return energy;
	}
	
	@Override
	public void cleanup() {
		if (queuePool != null) {
			queuePool.release(queue);
		}
		kernelBuilder.cleanup();
	}

	@Override
	public List<EnergyFunction> decomposeByDof(Molecule m, List<DegreeOfFreedom> dofs) {
		
		List<EnergyFunction> efuncs = new ArrayList<>();
		
		for (DegreeOfFreedom dof : dofs) {

			Residue res = dof.getResidue();
			if (res == null) {
				
				// when there's no residue at the dof, then use the whole efunc
				efuncs.add(this);
				
			} else {
				
				// otherwise, make an efunc for only that residue
				efuncs.add(new GpuForcefieldEnergy(this, interactions.makeSubsetByResidue(res)));
			}
		}
		
		return efuncs;
	}
}
