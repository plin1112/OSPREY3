package edu.duke.cs.osprey.lute;

import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.confspace.TuplesIndex;
import edu.duke.cs.osprey.energy.ConfEnergyCalculator;
import edu.duke.cs.osprey.energy.EnergyCalculator;
import edu.duke.cs.osprey.energy.ResidueInteractions;


public class LUTEConfEnergyCalculator extends ConfEnergyCalculator {

	public final LUTEState state;
	public final TuplesIndex tuples;

	public LUTEConfEnergyCalculator(SimpleConfSpace confSpace, EnergyCalculator ecalc, LUTEState state) {
		super(confSpace, ecalc, null, null, false);

		this.state = state;
		this.tuples = new TuplesIndex(confSpace, state.tuples);
	}

	@Override
	public ResidueInteractions makeSingleInters(int pos, int rc) {
		throw new UnsupportedOperationException("LUTE can only be used to compute full-conformation energies");
	}

	@Override
	public ResidueInteractions makePairInters(int pos1, int rc1, int pos2, int rc2) {
		throw new UnsupportedOperationException("LUTE can only be used to compute full-conformation energies");
	}

	@Override
	public ConfSearch.EnergiedConf calcEnergy(ConfSearch.ScoredConf conf) {
		return new ConfSearch.EnergiedConf(conf, calcEnergy(conf.getAssignments()));
	}

	@Override
	public ConfSearch.EnergiedConf calcEnergy(ConfSearch.ScoredConf conf, ResidueInteractions inters) {
		throw new UnsupportedOperationException("Not implemented yet... don't think anyone uses this anyway");
	}

	public double calcEnergy(int[] conf) {

		numCalculations.incrementAndGet();

		final boolean throwIfMissingSingle = false; // we're not fitting singles
		final boolean throwIfMissingPair = true; // we always fit to dense pairs, confs shouldn't be using pruned pairs

		// silly Java... If this were Kotlin, we wouldn't have to use an array to make the energy modifiable
		// hopefully JVM escape analysis will stack-allocate this?
		final double[] energy = new double[] { 0.0 };
		tuples.forEachIn(conf, throwIfMissingSingle, throwIfMissingPair, (t) -> {
			energy[0] += state.tupleEnergies[t];
		});
		return energy[0] + state.tupleEnergyOffset;
	}
}
