/*
** This file is part of OSPREY.
** 
** OSPREY is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation, either version 2 of the License, or
** (at your option) any later version.
** 
** OSPREY is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
** 
** You should have received a copy of the GNU General Public License
** along with OSPREY.  If not, see <http://www.gnu.org/licenses/>.
*/

package edu.duke.cs.osprey.lute;


import edu.duke.cs.osprey.astar.conf.ConfAStarTree;
import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.externalMemory.ExternalMemory;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.tools.BigMath;
import edu.duke.cs.osprey.tools.JvmMem;
import edu.duke.cs.osprey.tools.MathTools;
import edu.duke.cs.osprey.tools.Stopwatch;

import java.math.BigDecimal;
import java.math.BigInteger;


/**
 * Since LUTE energies are assumed to be correct, we don't have to deal with
 * minimization and bounding, so it's vastly easier to estimate partition functions.
 */
public class LUTEPfunc implements PartitionFunction {

	public final LUTEConfEnergyCalculator ecalc;

	private final BoltzmannCalculator bcalc = new BoltzmannCalculator(PartitionFunction.decimalPrecision);

	private boolean reportProgress = false;
	private ConfListener confListener = null;
	private ConfAStarTree astar = null;
	private BigInteger numConfsBeforePruning = null;
	private double epsilon;
	private BigDecimal stabilityThreshold = null;

	private PartitionFunction.Status status;
	private PartitionFunction.Values values;
	private int numConfsEvaluated;
	private Stopwatch stopwatch = new Stopwatch();

	public LUTEPfunc(LUTEConfEnergyCalculator ecalc) {
		this.ecalc = ecalc;
	}

	@Override
	public void setReportProgress(boolean val) {
		reportProgress = val;
	}

	@Override
	public void setConfListener(ConfListener val) {
		confListener = val;
	}

	@Override
	public void init(ConfSearch confSearch, BigInteger numConfsBeforePruning, double epsilon) {

		// make sure we got a LUTE-capable astar search
		if (!(confSearch instanceof ConfAStarTree)) {
			throw new IllegalArgumentException("needs LUTE-capable A* search");
		}
		this.astar = (ConfAStarTree)confSearch;
		if (!(astar.gscorer instanceof LUTEGScorer) || !(astar.hscorer instanceof LUTEHScorer)) {
			throw new IllegalArgumentException("needs LUTE-capable A* search");
		}

		this.numConfsBeforePruning = numConfsBeforePruning;
		this.epsilon = epsilon;

		status = Status.Estimating;
		values = Values.makeFullRange();
		numConfsEvaluated = 0;
		stopwatch.start();
	}

	@Override
	public void setStabilityThreshold(BigDecimal val) {
		this.stabilityThreshold = val;
	}

	@Override
	public Status getStatus() {
		return status;
	}

	@Override
	public Values getValues() {
		return values;
	}

	@Override
	public int getParallelism() {
		return ecalc.tasks.getParallelism();
	}

	@Override
	public int getNumConfsEvaluated() {
		return numConfsEvaluated;
	}

	@Override
	public void compute(int maxNumConfs) {

		// wow, this is SOOOOO much simpler than e.g. GradientDescentPfunc !!

		if (astar == null) {
			throw new IllegalStateException("pfunc was not initialized. Call init() before compute()");
		}

		for (int i=0; i<maxNumConfs; i++) {

			ConfSearch.ScoredConf conf = astar.nextConf();
			if (conf == null) {
				status = Status.OutOfConformations;
				break;
			}

			// confs are ordered by increasing score, so if we hit infinity, the rest of the confs are infinity too
			if (conf.getScore() == Double.POSITIVE_INFINITY) {
				status = Status.OutOfLowEnergies;
				break;
			}

			// we did a conf! =D
			numConfsEvaluated++;
			BigInteger numConfsLeft = numConfsBeforePruning.subtract(BigInteger.valueOf(numConfsEvaluated));

			if (confListener != null) {
				confListener.onConf(conf);
			}

			if (reportProgress) {
				System.out.println(String.format("conf:%4d, score:%12.6f, bounds:[%12e,%12e], delta:%.6f, time:%10s, heapMem:%s, extMem:%s",
					numConfsEvaluated,
					conf.getScore(),
					values.calcLowerBound().doubleValue(), values.calcUpperBound().doubleValue(),
					values.getEffectiveEpsilon(),
					stopwatch.getTime(2),
					JvmMem.getOldPool(),
					ExternalMemory.getUsageReport()
				));
			}

			// get the weight for this conf
			BigDecimal weight = bcalc.calc(conf.getScore());

			// update pfunc values
			values.qstar = values.qstar.add(weight);
			values.qprime = new BigMath(PartitionFunction.decimalPrecision)
				.set(weight)
				.mult(numConfsLeft)
				.get();

			// did we reach epsilon yet?
			if (values.getEffectiveEpsilon() <= epsilon) {
				status = Status.Estimated;
				break;
			}

			// are we unstable?
			if (stabilityThreshold != null && MathTools.isLessThan(values.calcUpperBound(), stabilityThreshold)) {
				status = Status.Unstable;
				break;
			}
		}
	}
}