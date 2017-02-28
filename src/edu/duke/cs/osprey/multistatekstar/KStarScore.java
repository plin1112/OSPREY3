package edu.duke.cs.osprey.multistatekstar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;

import edu.duke.cs.osprey.control.ConfSearchFactory;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.pruning.PruningMatrix;

public class KStarScore {

	public MultiStateKStarSettings settings;
	public PartitionFunction[] partitionFunctions;
	public int numStates;

	public KStarScore(MultiStateKStarSettings settings) {
		this.settings = settings;
		numStates = settings.search.length;
		partitionFunctions = new PartitionFunction[numStates];
	}

	public double getKStarScore() {
		PartitionFunction pf;
		BigDecimal ans = BigDecimal.ONE; int state;
		for(state=0;state<partitionFunctions.length-1;++state) {
			pf = partitionFunctions[state];
			if(pf.getValues().qstar.compareTo(BigDecimal.ZERO)==0)
				return 0.0;
			ans = ans.multiply(pf.getValues().qstar);
		}
		pf = partitionFunctions[state];
		return pf.getValues().qstar.divide(ans, RoundingMode.HALF_UP).doubleValue();
	}

	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("Seq: "+settings.getFormattedSequence()+", ");
		sb.append(String.format("score: %12e, ", getKStarScore()));
		for(int state=0;state<numStates;++state)
			sb.append(String.format("pf: %2d, q*: %12e, ", state, partitionFunctions[state].getValues().qstar));
		String ans = sb.toString().trim();
		return ans.substring(0,ans.length()-1);
	}

	/**
	 * compute until maxNumConfs conformations have been processed
	 * @param maxNumConfs
	 */
	public void compute(int maxNumConfs) {

		for(int state=0;state<numStates;++state){	
			//prune matrix
			settings.search[state].prunePmat(settings.search[state], settings.pruningWindow, settings.stericThreshold);

			//make conf search factory (i.e. A* tree)
			ConfSearchFactory confSearchFactory = MultiStateKStarSettings.makeConfSearchFactory(settings.search[state], settings.cfp);

			//create partition function
			partitionFunctions[state] = MultiStateKStarSettings.makePartitionFunction( 
					settings.search[state].emat, 
					settings.search[state].pruneMat,
					confSearchFactory,
					settings.ecalcs[state]
					);

			partitionFunctions[state].setReportProgress(settings.isReportingProgress);

			//init partition function
			partitionFunctions[state].init(settings.targetEpsilon);

			compute(state, maxNumConfs);
		}
	}

	/**
	 * compute until a conf score boltzmann weight of minbound has been processed.
	 * this is used in the second phase to process confs from p*
	 */
	private BigDecimal phase2(int state) {
		// we have p* / q* = epsilon1 > target epsilon
		// we want p1* / q* <= target epsilon
		// therefore, p1* <= q* x target epsilon
		// we take p1* as our new value of p* and shift 
		// the pairwise lower bound probability mass 
		// of p* - p1* to q*.
		// this is accomplished by enumerating confs in p*
		// until BoltzmannE(sum_scoreWeights) >= p* - p1*

		PartitionFunction pf = partitionFunctions[state];
		BigDecimal targetScoreWeights;
		double epsilon = pf.getValues().getEffectiveEpsilon();
		double targetEpsilon = settings.targetEpsilon;
		BigDecimal qstar = pf.getValues().qstar;
		BigDecimal qprime = pf.getValues().qprime;
		BigDecimal pstar = pf.getValues().pstar;

		if(epsilon==1.0) {
			targetScoreWeights = pf.getValues().pstar;
		}

		else {
			targetScoreWeights = BigDecimal.valueOf(targetEpsilon/(1.0-targetEpsilon));
			targetScoreWeights = targetScoreWeights.multiply(qstar);
			targetScoreWeights = (pstar.add(qprime)).subtract(targetScoreWeights);
		}

		//System.out.println("eps: "+epsilon+" targetEps: "+settings.targetEpsilon+" q': "+qprime);

		PruningMatrix invMat = ((QPruningMatrix)settings.search[state].pruneMat).invert();
		settings.search[state].pruneMat = invMat;

		ConfSearchFactory confSearchFactory = MultiStateKStarSettings.makeConfSearchFactory(settings.search[state], settings.cfp);

		PartitionFunction phase2PF = MultiStateKStarSettings.makePartitionFunction( 
				settings.search[state].emat, 
				settings.search[state].pruneMat, 
				confSearchFactory,
				settings.ecalcs[state]
				);

		phase2PF.init(0.0);

		((ParallelPartitionFunction)phase2PF).compute(targetScoreWeights);
		BigDecimal ans = phase2PF.getValues().qstar;
		return ans;
	}

	private void compute(int state, int maxNumConfs) {
		if(settings.isReportingProgress) System.out.println("state"+state+": "+settings.getFormattedSequence());
		PartitionFunction pf = partitionFunctions[state];
		pf.compute(maxNumConfs);	

		//no more q conformations, and we have not reached epsilon
		if(pf.getValues().getEffectiveEpsilon() > settings.targetEpsilon) {	
			BigDecimal phase2Qstar = phase2(state);
			pf.getValues().qstar = pf.getValues().qstar.add(phase2Qstar);
		}
	}

	private ArrayList<LMV> getLMVsForStateOnly(int state) {
		if(settings.constraints==null) return null;
		ArrayList<LMV> ans = new ArrayList<>();

		for(int l=0;l<settings.constraints.length;++l){
			BigDecimal[] coeffs = settings.constraints[l].coeffs;
			if(coeffs[state].compareTo(BigDecimal.ZERO)==0) continue;
			for(int c=0;c<coeffs.length;++c){
				if(coeffs[state].compareTo(BigDecimal.ZERO)!=0 && c!= state) break;
			}
			ans.add(settings.constraints[l]);
		}

		return ans;
	}
}
