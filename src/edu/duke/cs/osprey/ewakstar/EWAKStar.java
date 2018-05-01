package edu.duke.cs.osprey.ewakstar;

import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.confspace.ConfDB;
import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.ematrix.SimplerEnergyMatrixCalculator;
import edu.duke.cs.osprey.energy.ConfEnergyCalculator;
import edu.duke.cs.osprey.energy.EnergyCalculator;
import edu.duke.cs.osprey.kstar.KStar;
import edu.duke.cs.osprey.kstar.KStarScore;
import edu.duke.cs.osprey.kstar.KStarScoreWriter;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.GradientDescentPfunc;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.tools.MathTools;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

public class EWAKStar {

    public static interface ConfEnergyCalculatorFactory {
        ConfEnergyCalculator make(SimpleConfSpace confSpace, EnergyCalculator ecalc);
    }

    public static interface ConfSearchFactory {
        public ConfSearch make(EnergyMatrix emat, RCs rcs);
    }

    // *sigh* Java makes this stuff so verbose to do...
    // Kotlin would make this so much easier
    public static class Settings {

        public static class Builder {

            /**
             * Value of epsilon in (0,1] for the epsilon-approximation to a partition function.
             *
             * Smaller values for epsilon yield more accurate predictions, but can take
             * longer to run.
             */
            private double epsilon = 0.683;

            /** For EWAKStar we want to calculate each score for a certain energy window
             * and not to an epsilon approximation
             */
            private double eW = 2.0;

            /**
             * Pruning criteria to remove sequences with unstable unbound states relative to the wild type sequence.
             * Defined in units of kcal/mol.
             *
             * More precisely, a sequence is pruned when the following expression is true:
             *
             * U(Z_s) < L(W_s) * B(t)
             *
             * where:
             *   - s represents the unbound protein strand, or unbound ligand strand
             *   - U(Z_s) is the upper bound on the partition function for strand s
             *   - L(W_s) is the lower bound on the partition function for strand s in the wild type
             *   - t is the stability threshold
             *   - B() is the Boltzmann weighting function
             *
             * Set to null to disable the filter entirely.
             */
            private Double stabilityThreshold = 5.0;

            private KStarScoreWriter.Writers scoreWriters = new KStarScoreWriter.Writers();

            /**
             * If true, prints out information to the console for each minimized conformation during
             * partition function approximation
             */
            private boolean showPfuncProgress = false;

            /**
             * Pattern of the filename to cache energy matrices.
             *
             * K*-type algorithms must calculate multiple energy matrices.
             * By default, these energy matrices are not cached between runs.
             * To cache energy matrices between runs, supply a pattern such as:
             *
             * "theFolder/emat.*.dat"
             *
             * The * in the pattern is a wildcard character that will be replaced with
             * each type of energy matrix used by the K*-type algorithm.
             */
            private String energyMatrixCachePattern = null;

            public EWAKStar.Settings.Builder setEw(double val){
                eW = val;
                return this;
            }

            public EWAKStar.Settings.Builder setStabilityThreshold(Double val) {
                if (val != null && val.isInfinite()) {
                    throw new IllegalArgumentException("only finite values allowed. To turn off the filter, pass null");
                }
                stabilityThreshold = val;
                return this;
            }

            public EWAKStar.Settings.Builder addScoreWriter(KStarScoreWriter val) {
                scoreWriters.add(val);
                return this;
            }

            public EWAKStar.Settings.Builder addScoreConsoleWriter(KStarScoreWriter.Formatter val) {
                return addScoreWriter(new KStarScoreWriter.ToConsole(val));
            }

            public EWAKStar.Settings.Builder addScoreConsoleWriter() {
                return addScoreConsoleWriter(new KStarScoreWriter.Formatter.SequenceKStarPfuncs());
            }

            public EWAKStar.Settings.Builder addScoreFileWriter(File file, KStarScoreWriter.Formatter val) {
                return addScoreWriter(new KStarScoreWriter.ToFile(file, val));
            }

            public EWAKStar.Settings.Builder addScoreFileWriter(File file) {
                return addScoreFileWriter(file, new KStarScoreWriter.Formatter.Log());
            }

            public EWAKStar.Settings.Builder setShowPfuncProgress(boolean val) {
                showPfuncProgress = val;
                return this;
            }

            public EWAKStar.Settings.Builder setEnergyMatrixCachePattern(String val) {
                energyMatrixCachePattern = val;
                return this;
            }

            public EWAKStar.Settings build() {
                return new EWAKStar.Settings(epsilon, eW, stabilityThreshold, scoreWriters, showPfuncProgress, energyMatrixCachePattern);
            }
        }

        public final double epsilon;
        public final double eW;
        public final Double stabilityThreshold;
        public final KStarScoreWriter.Writers scoreWriters;
        public final boolean showPfuncProgress;
        public final String energyMatrixCachePattern;

        public Settings(double epsilon, double eW, Double stabilityThreshold, KStarScoreWriter.Writers scoreWriters, boolean dumpPfuncConfs, String energyMatrixCachePattern) {
            this.epsilon = epsilon;
            this.eW = eW;
            this.stabilityThreshold = stabilityThreshold;
            this.scoreWriters = scoreWriters;
            this.showPfuncProgress = dumpPfuncConfs;
            this.energyMatrixCachePattern = energyMatrixCachePattern;
        }

        public String applyEnergyMatrixCachePattern(String type) {

            // the pattern has a * right?
            if (energyMatrixCachePattern.indexOf('*') < 0) {
                throw new IllegalArgumentException("energyMatrixCachePattern (which is '" + energyMatrixCachePattern + "') has no wildcard character (which is *)");
            }

            return energyMatrixCachePattern.replace("*", type);
        }
    }

    public static class ScoredSequence {

        public final Sequence sequence;
        public final KStarScore score;

        public ScoredSequence(Sequence sequence, KStarScore score) {
            this.sequence = sequence;
            this.score = score;
        }

        @Override
        public String toString() {
            return "sequence: " + sequence + "   K*(log10): " + score;
        }

        public String toString(Sequence wildtype) {
            return "sequence: " + sequence.toString(Sequence.Renderer.AssignmentMutations) + "   K*(log10): " + score;
        }
    }

    public static enum ConfSpaceType {
        Protein,
        Ligand,
        Complex
    }

    public class ConfSpaceInfo {

        public final EWAKStar.ConfSpaceType type;
        public final SimpleConfSpace confSpace;
        public final ConfEnergyCalculator confEcalc;

        public final List<Sequence> sequences = new ArrayList<>();
        public EnergyMatrix emat = null;
        public final Map<Sequence,PartitionFunction.Result> pfuncResults = new HashMap<>();

        public ConfSpaceInfo(EWAKStar.ConfSpaceType type, SimpleConfSpace confSpace, ConfEnergyCalculator confEcalc) {
            this.type = type;
            this.confSpace = confSpace;
            this.confEcalc = confEcalc;
        }

        public void calcEmat() {
            SimplerEnergyMatrixCalculator.Builder builder = new SimplerEnergyMatrixCalculator.Builder(confEcalc);
            if (settings.energyMatrixCachePattern != null) {
                builder.setCacheFile(new File(settings.applyEnergyMatrixCachePattern(type.name().toLowerCase())));
            }
            emat = builder.build().calcEnergyMatrix();
        }

        public PartitionFunction.Result calcPfunc(int sequenceIndex, BigDecimal stabilityThreshold, ConfDB confDB) {

            Sequence sequence = sequences.get(sequenceIndex);

            // check the cache first
            PartitionFunction.Result result = pfuncResults.get(sequence);
            if (result != null) {
                return result;
            }

            // cache miss, need to compute the partition function

            // make the partition function
            ConfSearch astar = confSearchFactory.make(emat, sequence.makeRCs());
            GradientDescentPfunc pfunc = new GradientDescentPfunc(astar, confEcalc);
            pfunc.setReportProgress(settings.showPfuncProgress);
            if (confDB != null) {
                pfunc.setConfTable(confDB.getSequence(sequence));
            }

            // compute it
            pfunc.init(settings.epsilon, stabilityThreshold);
            pfunc.compute();

            // save the result
            result = pfunc.makeResult();
            pfuncResults.put(sequence, result);
            return result;
        }

    }

    private static interface Scorer {
        KStarScore score(int sequenceNumber, PartitionFunction.Result proteinResult, PartitionFunction.Result ligandResult, PartitionFunction.Result complexResult);
    }

    /** A configuration space containing just the protein strand */
    public final EWAKStar.ConfSpaceInfo protein;

    /** A configuration space containing just the ligand strand */
    public final EWAKStar.ConfSpaceInfo ligand;

    /** A configuration space containing both the protein and ligand strands */
    public final EWAKStar.ConfSpaceInfo complex;

    /** Calculates the energy for a molecule */
    public final EnergyCalculator ecalc;

    /** A function that makes a ConfEnergyCalculator with the desired options */
    public final EWAKStar.ConfEnergyCalculatorFactory confEcalcFactory;

    /** A function that makes a ConfSearchFactory (e.g, A* search) with the desired options */
    public final EWAKStar.ConfSearchFactory confSearchFactory;

    /** Optional and overridable settings for K* */
    public final EWAKStar.Settings settings;

	public EWAKStar(SimpleConfSpace protein, SimpleConfSpace ligand, SimpleConfSpace complex, EnergyCalculator ecalc, EWAKStar.ConfEnergyCalculatorFactory confEcalcFactory, EWAKStar.ConfSearchFactory confSearchFactory, EWAKStar.Settings settings) {
        this.protein = new EWAKStar.ConfSpaceInfo(EWAKStar.ConfSpaceType.Protein, protein, confEcalcFactory.make(protein, ecalc));
        this.ligand = new EWAKStar.ConfSpaceInfo(EWAKStar.ConfSpaceType.Ligand, ligand, confEcalcFactory.make(ligand, ecalc));
        this.complex = new EWAKStar.ConfSpaceInfo(EWAKStar.ConfSpaceType.Complex, complex, confEcalcFactory.make(complex, ecalc));
        this.ecalc = ecalc;
        this.confEcalcFactory = confEcalcFactory;
        this.confSearchFactory = confSearchFactory;
        this.settings = settings;
    }
}
