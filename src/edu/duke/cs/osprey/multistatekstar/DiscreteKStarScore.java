package edu.duke.cs.osprey.multistatekstar;

import java.math.BigDecimal;

public class DiscreteKStarScore extends ContinuousKStarScore {

	public DiscreteKStarScore(KStarSettings settings) {
		super(settings);
	}

	@Override
	public BigDecimal getLowerBoundScore() {
		return getScore();
	}

	@Override
	public BigDecimal getUpperBoundScore() {
		return getScore();
	}

}