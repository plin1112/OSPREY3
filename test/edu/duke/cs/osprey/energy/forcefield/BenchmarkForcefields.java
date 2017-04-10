package edu.duke.cs.osprey.energy.forcefield;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import edu.duke.cs.osprey.TestBase;
import edu.duke.cs.osprey.confspace.RCTuple;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.confspace.Strand;
import edu.duke.cs.osprey.energy.EnergyFunction;
import edu.duke.cs.osprey.energy.MultiTermEnergyFunction;
import edu.duke.cs.osprey.energy.ResInterGen;
import edu.duke.cs.osprey.energy.ResidueInteractions;
import edu.duke.cs.osprey.parallelism.Parallelism;
import edu.duke.cs.osprey.structure.AtomConnectivity;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.PDBIO;
import edu.duke.cs.osprey.structure.Residue;
import edu.duke.cs.osprey.tools.Stopwatch;
import edu.duke.cs.osprey.tools.TimeFormatter;

public class BenchmarkForcefields extends TestBase {
	
	private static class Result {
		
		double ms1;
		double ms2;
		
		public Result(double ms1, double ms2) {
			this.ms1 = ms1;
			this.ms2 = ms2;
		}
	}
	
	public static void main(String[] args) {
		
		ForcefieldParams ffparams = new ForcefieldParams();
		
		// get a conf space
		Strand strand = new Strand.Builder(PDBIO.readFile("examples/1CC8.python/1CC8.ss.pdb")).build();
		for (int i=2; i<=6; i++) {
			strand.flexibility.get(i).setLibraryRotamers();
		}
		SimpleConfSpace confSpace = new SimpleConfSpace.Builder().addStrand(strand).build();
		
		// pre-compute atom connectivities
		AtomConnectivity connectivity = new AtomConnectivity.Builder()
			.setConfSpace(confSpace)
			.setParallelism(Parallelism.makeCpu(4))
			.build();
		
		// get a molecule
		Molecule mol = confSpace.makeMolecule(new int[] { 0, 0, 0, 0, 0 }).mol;
		
		// multi term energy function
		Result base = benchmark(MultiTermEnergyFunction.class.getSimpleName(), null, () -> {
			MultiTermEnergyFunction efunc = new MultiTermEnergyFunction();
			for (int pos1=0; pos1<confSpace.positions.size(); pos1++) {
				Residue res1 = mol.getResByPDBResNumber(confSpace.positions.get(pos1).resNum);
				efunc.addTerm(new SingleResEnergy(res1, ffparams));
				for (int pos2=0; pos2<pos1; pos2++) {
					Residue res2 = mol.getResByPDBResNumber(confSpace.positions.get(pos2).resNum);
					efunc.addTerm(new ResPairEnergy(res1, res2, ffparams));
				}
			}
			return efunc;
		});
		
		// residue forcefield
		RCTuple frag = new RCTuple(new int[] { 0, 0, 0, 0, 0 });
		benchmark(ResidueForcefieldEnergy.class.getSimpleName(), base, () -> {
			ResidueInteractions inters = ResInterGen.of(confSpace)
				.addIntras(frag)
				.addInters(frag)
				.make();
			return new ResidueForcefieldEnergy(ffparams, inters, mol, connectivity);
		});
		
		// big forcefield
		benchmark(BigForcefieldEnergy.class.getSimpleName(), base, () -> {
			ForcefieldInteractions ffinters = new ForcefieldInteractions();
			for (int pos1=0; pos1<confSpace.positions.size(); pos1++) {
				Residue res1 = mol.getResByPDBResNumber(confSpace.positions.get(pos1).resNum);
				ffinters.addResidue(res1);
				for (int pos2=0; pos2<pos1; pos2++) {
					Residue res2 = mol.getResByPDBResNumber(confSpace.positions.get(pos2).resNum);
					ffinters.addResiduePair(res1, res2);
				}
			}
			return new BigForcefieldEnergy(ffparams, ffinters);
		});
	}
	
	private static Result benchmark(String name, Result base, Supplier<EnergyFunction> efuncs) {
		
		System.out.println(name);
		
		Result result = new Result(0, 0);
		
		// first benchmark, create-run-cleanup cycles
		result.ms1 = benchmark("create-run-cleanup", 100, 1000, 3, base == null ? null : base.ms1, () -> {
			makeAndRunForcefield(efuncs);
		});
		
		// second benchmark, run cycles
		EnergyFunction efunc = efuncs.get();
		result.ms2 = benchmark("run", 100, 6000, 3, base == null ? null : base.ms2, () -> {
			efunc.getEnergy();
		});
		cleanup(efunc);
		
		return result;
	}
	
	private static double benchmark(String name, int numWarmupRuns, int numRuns, int replicates, Double baseMs, Runnable iter) {
		
		List<Double> ms = new ArrayList<>();
		
		for (int repl=0; repl<replicates; repl++) {
			
			// warm up first
			for (int i=0; i<numWarmupRuns; i++) {
				iter.run();
			}
			
			// time it for reals
			Stopwatch stopwatch = new Stopwatch().start();
			for (int i=0; i<numRuns; i++) {
				iter.run();
			}
			stopwatch.stop();
			
			// show the results
			System.out.print(String.format("\t%22s finished in %10s   %6.1f ops",
				name,
				stopwatch.stop().getTime(2),
				numRuns/stopwatch.getTimeS()
			));
			if (baseMs != null) {
				System.out.print(String.format("   %.2fx speedup", baseMs/stopwatch.getTimeMs()));
			}
			System.out.println();
			
			ms.add(stopwatch.getTimeMs());
		}
		
		// get the best results
		double bestMs = ms.stream()
			.reduce((Double a, Double b) -> Math.min(a, b))
			.get();
		
		System.out.print(String.format("\t                     best time: %10s   %6.1f ops",
			TimeFormatter.format((long)(bestMs*1000*1000), 2),
			(double)numRuns/bestMs*1000
		));
		if (baseMs != null) {
			System.out.print(String.format("   %.2fx speedup", baseMs/bestMs));
		}
		System.out.println();
		
		System.out.println();
		
		return bestMs;
	}
	
	private static void makeAndRunForcefield(Supplier<EnergyFunction> efuncs) {
		EnergyFunction efunc = efuncs.get();
		efunc.getEnergy();
		cleanup(efunc);
	}
	
	private static void cleanup(EnergyFunction efunc) {
		if (efunc instanceof EnergyFunction.NeedsCleanup) {
			((EnergyFunction.NeedsCleanup)efunc).cleanup();
		}
	}
}
