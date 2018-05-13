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

package edu.duke.cs.osprey.confspace;


import java.util.*;
import java.util.function.Consumer;


public class TuplesIndex implements Iterable<RCTuple> {

	public static class NoSuchTupleException extends RuntimeException {

		public NoSuchTupleException(RCTuple tuple) {
			super("no tuple found matching " + tuple);
		}
	}

	public final SimpleConfSpace confSpace;

	private final List<RCTuple> tuples;
	private final TupleMatrixGeneric<Integer> index;

	public TuplesIndex(SimpleConfSpace confSpace, RCTuple[] tuplesArray) {
		this(confSpace, Arrays.asList(tuplesArray));
	}

	public TuplesIndex(SimpleConfSpace confSpace, Collection<RCTuple> tuplesCollection) {

		this.confSpace = confSpace;
		this.tuples = new ArrayList<>(tuplesCollection);

		// index the tuples
		index = new TupleMatrixGeneric<>(confSpace);
		index.fill((Integer)null);
		for (int i=0; i<tuples.size(); i++) {
			index.setTuple(tuples.get(i), i);
		}
	}

	@Override
	public Iterator<RCTuple> iterator() {
		// don't let callers modify the tuples list after indexing
		// so make new Iterator subclass so that remove() throws an exception
		return new Iterator<RCTuple>() {

			Iterator<RCTuple> iter = tuples.iterator();

			@Override
			public boolean hasNext() {
				return iter.hasNext();
			}

			@Override
			public RCTuple next() {
				return iter.next();
			}
		};
	}

	public RCTuple get(int index) {
		return tuples.get(index);
	}

	public int size() {
		return tuples.size();
	}

	public Integer getIndex(int pos1, int rc1) {
		return index.getOneBody(pos1, rc1);
	}

	public Integer getIndex(int pos1, int rc1, int pos2, int rc2) {
		return index.getPairwise(pos1, rc1, pos2, rc2);
	}

	public Integer getIndex(int pos1, int rc1, int pos2, int rc2, int pos3, int rc3) {
		// TODO: is there a more efficient way to do this?
		return getIndex(new RCTuple(pos1, rc1, pos2, rc2, pos3, rc3).sorted());
	}

	public Integer getIndex(RCTuple tuple) {
		return index.getTuple(tuple);
	}

	public boolean contains(int pos1, int rc1) {
		return getIndex(pos1, rc1) != null;
	}

	public boolean contains(int pos1, int rc1, int pos2, int rc2) {
		return getIndex(pos1, rc1, pos2, rc2) != null;
	}

	public boolean contains(int pos1, int rc1, int pos2, int rc2, int pos3, int rc3) {
		// TODO: is there a more efficient way to do this?
		return getIndex(new RCTuple(pos1, rc1, pos2, rc2, pos3, rc3).sorted()) != null;
	}

	public boolean contains(RCTuple tuple) {
		return getIndex(tuple) != null;
	}

	public boolean isAssignmentCoveredByPairs(int[] conf, int nextPos, int nextRC) {

		// all pairs between this possible assignment and the conf assignments must be present in the tuple set
		for (int pos=0; pos<conf.length; pos++) {

			// skip unassigned positions
			int rc = conf[pos];
			if (rc == Conf.Unassigned) {
				continue;
			}

			// don't assign the same position twice
			if (pos == nextPos) {
				throw new IllegalArgumentException(String.format("pos %d already assigned in conf %s", nextPos, Conf.toString(conf)));
			}

			// is this pair present in the set?
			if (!contains(pos, rc, nextPos, nextRC)) {
				return false;
			}
		}

		return true;
	}

	public void forEachIn(int[] conf, boolean throwIfMissingSingle, boolean throwIfMissingPair, Consumer<Integer> callback) {

		// look for pairs first
		// complain loudly if the conf has pair tuples that aren't in our list
		// pair tuples are supposed to be completely covered
		int numPos = confSpace.positions.size();
		for (int pos1=0; pos1<numPos; pos1++) {

			int rc1 = conf[pos1];
			if (rc1 == Conf.Unassigned) {
				continue;
			}

			Integer t = index.getOneBody(pos1, rc1);
			if (t != null) {
				callback.accept(t);
			} else if (throwIfMissingSingle) {
				throw new TuplesIndex.NoSuchTupleException(new RCTuple(pos1, rc1));
			}

			for (int pos2=0; pos2<pos1; pos2++) {

				int rc2 = conf[pos2];
				if (rc2 == Conf.Unassigned) {
					continue;
				}

				t = index.getPairwise(pos1, rc1, pos2, rc2);
				if (t != null) {
					callback.accept(t);
				} else if (throwIfMissingPair) {
					throw new TuplesIndex.NoSuchTupleException(new RCTuple(pos1, rc1, pos2, rc2));
				}
			}
		}

		// look for higher order tuples next
		index.forEachHigherOrderTupleIn(conf, (tuple, index) -> callback.accept(index));
	}
}