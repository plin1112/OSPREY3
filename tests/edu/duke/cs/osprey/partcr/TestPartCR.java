package edu.duke.cs.osprey.partcr;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import edu.duke.cs.osprey.TestBase;
import edu.duke.cs.osprey.astar.conf.ConfAStarTree;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.astar.conf.order.AStarOrder;
import edu.duke.cs.osprey.astar.conf.order.StaticScoreHMeanAStarOrder;
import edu.duke.cs.osprey.astar.conf.scoring.AStarScorer;
import edu.duke.cs.osprey.astar.conf.scoring.MPLPPairwiseHScorer;
import edu.duke.cs.osprey.astar.conf.scoring.PairwiseGScorer;
import edu.duke.cs.osprey.astar.conf.scoring.mplp.NodeUpdater;
import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.confspace.ConfSearch.EnergiedConf;
import edu.duke.cs.osprey.confspace.SearchProblem;
import edu.duke.cs.osprey.control.EnvironmentVars;
import edu.duke.cs.osprey.control.GMECFinder;
import edu.duke.cs.osprey.dof.deeper.DEEPerSettings;
import edu.duke.cs.osprey.ematrix.SimpleEnergyCalculator;
import edu.duke.cs.osprey.ematrix.epic.EPICSettings;
import edu.duke.cs.osprey.energy.MultiTermEnergyFunction;
import edu.duke.cs.osprey.pruning.PruningControl;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.tools.Factory;
import edu.duke.cs.osprey.tupexp.LUTESettings;

public class TestPartCR extends TestBase {
	
	@BeforeClass
	public static void before() {
		initDefaultEnvironment();
		MultiTermEnergyFunction.setNumThreads(4);
	}
	
	@Test
	public void testPartCr()
	throws Exception {
		
		SearchProblem search = makeSearch();
		
		// calc the energy matrix once
		SimpleEnergyCalculator ecalc = new SimpleEnergyCalculator(EnvironmentVars.curEFcnGenerator, search.confSpace, search.shellResidues);
		search.emat = ecalc.calcEnergyMatrix();
		
		List<EnergiedConf> expectedConfs = getConfs(search, false);
		List<EnergiedConf> partcrConfs = getConfs(search, true);
		
		// first make sure the expected confs are actually what we expect
		EnergiedConf minGMEC = expectedConfs.get(0);
		assertThat(minGMEC.getAssignments(), is(new int[] { 23, 23, 3, 5 }));
		assertThat(minGMEC.getScore(), isRelatively(-66.883873, 1e-6));
		assertThat(minGMEC.getEnergy(), isRelatively(-64.270196, 1e-6));
		assertThat(expectedConfs.size(), is(16));
		
		// then make sure PartCR did the right thing
		// the two conf lists should be identical, PartCR is only an optimization
		// except for maybe the minimized energies
		// the minimizer is slightly non-deterministic, or the initial conditions change over time
		final double scoreEpsilon = 1e-10;
		final double energyEpsilon = 1e-6;
		assertThat(expectedConfs.size(), is(partcrConfs.size()));
		for (int i=0; i<expectedConfs.size(); i++) {
			
			EnergiedConf expectedConf = expectedConfs.get(i);
			EnergiedConf partcrConf = partcrConfs.get(i);
			
			assertThat(expectedConf.getAssignments(), is(partcrConf.getAssignments()));
			assertThat(expectedConf.getScore(), isRelatively(partcrConf.getScore(), scoreEpsilon));
			assertThat(expectedConf.getEnergy(), isRelatively(partcrConf.getEnergy(), energyEpsilon));
		}
	}
	
	private SearchProblem makeSearch() {
		
		String aaNames = "ALA VAL LEU ILE";
		//String aaNames = "ALA";
		String mutRes = "39 43";
		String flexRes = "40 41";
		ArrayList<String> flexResList = new ArrayList<>();
		ArrayList<ArrayList<String>> allowedAAs = new ArrayList<>();
		for (String res : mutRes.split(" ")) {
			if (!res.isEmpty()) {
				flexResList.add(res);
				allowedAAs.add(new ArrayList<>(Arrays.asList(aaNames.split(" "))));
			}
		}
		for (String res : flexRes.split(" ")) {
			if (!res.isEmpty()) {
				flexResList.add(res);
				allowedAAs.add(new ArrayList<>());
			}
		}
		boolean doMinimize = true;
		boolean addWt = true;
		boolean useEpic = false;
		boolean useTupleExpansion = false;
		boolean useEllipses = false;
		boolean useERef = false;
		boolean addResEntropy = false;
		boolean addWtRots = false;
		ArrayList<String[]> moveableStrands = new ArrayList<String[]>();
		ArrayList<String[]> freeBBZones = new ArrayList<String[]>();
		return new SearchProblem(
			"test", "test/1CC8/1CC8.ss.pdb", 
			flexResList, allowedAAs, addWt, doMinimize, useEpic, new EPICSettings(), useTupleExpansion, new LUTESettings(),
			new DEEPerSettings(), moveableStrands, freeBBZones, useEllipses, useERef, addResEntropy, addWtRots, null
		);
	}
	
	private List<EnergiedConf> getConfs(SearchProblem search, boolean usePartCR)
	throws Exception {
		
		// configure DEE
		// don't worry about the pruning interval now, GMECFinder will configure it later
		double pruningInterval = 0;
		search.pruneMat = new PruningMatrix(search.confSpace, pruningInterval);
		boolean typeDep = false;
		double boundsThresh = 100;
		int algOption = 1;
		boolean useFlags = true;
		boolean useTriples = false;
		boolean preDACS = false;
		boolean useTupExp = false;
		double stericThresh = 100;
		PruningControl pruningControl = new PruningControl(
			search, pruningInterval, typeDep, boundsThresh, algOption,
			useFlags, useTriples, preDACS, search.useEPIC, useTupExp, stericThresh
		);
		pruningControl.setReportMode(PruningControl.ReportMode.Short);
		
		// configure A* search
		Factory<ConfSearch,SearchProblem> astarFactory = new Factory<ConfSearch,SearchProblem>() {
			@Override
			public ConfSearch make(SearchProblem search) {
				RCs rcs = new RCs(search.pruneMat);
				AStarOrder order = new StaticScoreHMeanAStarOrder();
				AStarScorer hscorer = new MPLPPairwiseHScorer(new NodeUpdater(), search.emat, 1, 0.0001);
				ConfAStarTree astarTree = new ConfAStarTree(order, new PairwiseGScorer(search.emat), hscorer, rcs);
				astarTree.initProgress();
				return astarTree;
			}
		};
		
		// configure the GMEC finder
		// NOTE: PartCR doesn't help as much with energy window designs
		// but we still want to test that it works correctly
		double I0 = 10;
		double Ew = 1;
		boolean useIMinDEE = true;
		boolean useContFlex = true;
		boolean useEPIC = false;
		boolean checkApproxE = true;
		boolean outputGMECStruct = false;
		boolean eFullConfOnly = false;
		File tmpFile = File.createTempFile("partcrTestConfs", ".txt");
		tmpFile.deleteOnExit();
		GMECFinder gmecFinder = new GMECFinder();
		gmecFinder.init(
			search, pruningControl, astarFactory, Ew, useIMinDEE, I0, useContFlex, useTupExp, useEPIC,
			checkApproxE, outputGMECStruct, eFullConfOnly, tmpFile.getAbsolutePath(), stericThresh
		);
		gmecFinder.setLogConfsToConsole(false);
		
		// configure PartCR if needed
		if (usePartCR) {
			gmecFinder.setConfPruner(new PartCRConfPruner(search, Ew));
		}
		
		// GO GO GO!!
		return gmecFinder.calcGMEC();
	}
}