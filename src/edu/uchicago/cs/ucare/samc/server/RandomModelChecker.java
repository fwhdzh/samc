package edu.uchicago.cs.ucare.samc.server;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Random;
import com.almworks.sqlite4java.SQLiteException;

import edu.uchicago.cs.ucare.samc.transition.NodeOperationTransition;
import edu.uchicago.cs.ucare.samc.transition.PacketSendTransition;
import edu.uchicago.cs.ucare.samc.transition.Transition;
import edu.uchicago.cs.ucare.samc.transition.AbstractNodeOperationTransition;
import edu.uchicago.cs.ucare.samc.transition.AbstractNodeCrashTransition;
import edu.uchicago.cs.ucare.samc.transition.AbstractNodeStartTransition;
import edu.uchicago.cs.ucare.samc.event.Event;
import edu.uchicago.cs.ucare.samc.raft.AbstractRaftSnapshot;
import edu.uchicago.cs.ucare.samc.util.WorkloadDriver;
import edu.uchicago.cs.ucare.samc.util.ExploredBranchRecorder;
import edu.uchicago.cs.ucare.samc.util.LocalState;
import edu.uchicago.cs.ucare.samc.util.SqliteExploredBranchRecorder;

public class RandomModelChecker extends ModelCheckingServerAbstract {
    
    ExploredBranchRecorder exploredBranchRecorder;
    int numCrash;
    int numReboot;
    int currentCrash;
    int currentReboot;
    String stateDir;
    Random random;
    
    boolean enableNodeTwoEvent;
    boolean enableNodeTwoSync;
    
    public RandomModelChecker(String inceptorName, String ackName, int maxId,
            int numCrash, int numReboot, String globalStatePathDir, String packetRecordDir, String workingDir,
            WorkloadDriver workloadDriver, String ipcDir) {
        super(inceptorName, ackName, maxId, globalStatePathDir, workingDir, workloadDriver, ipcDir);
        this.numCrash = numCrash;
        this.numReboot = numReboot;
        stateDir = packetRecordDir;
        
        this.enableNodeTwoEvent = false;
        this.enableNodeTwoSync = false;
        
        try {
            exploredBranchRecorder = new SqliteExploredBranchRecorder(packetRecordDir);
        } catch (SQLiteException e) {
            LOG.error("", e);
        }
        random = new Random(System.currentTimeMillis());
        resetTest();
    }

    @Override
    public void resetTest() {
        if (exploredBranchRecorder == null) {
            return;
        }
        super.resetTest();
        currentCrash = 0;
        currentReboot = 0;
        modelChecking = new PathTraversalWorker();
        currentEnabledTransitions = new LinkedList<Transition>();
        exploredBranchRecorder.resetTraversal();
        File waiting = new File(stateDir + "/.waiting");
        try {
            waiting.createNewFile();
        } catch (IOException e) {
            LOG.error("", e);
        }
    }
    
    @SuppressWarnings("unchecked")
	public Transition nextTransition(LinkedList<Transition> transitions) {
    	LinkedList<Transition> cloneQueue = (LinkedList<Transition>) transitions.clone();
        while (cloneQueue.size()>0) {
        	int i = random.nextInt(cloneQueue.size());
        	Transition cloneTransition;
        	if(interceptorName.equals("zkChecker-3.5.1")) {
        		cloneTransition = cloneQueue.get(i);
            	if(cloneTransition instanceof PacketSendTransition) {
            		LocalState ls0 = ((PacketSendTransition) cloneTransition).getChecker().localStates[0];
            		LocalState ls1 = ((PacketSendTransition) cloneTransition).getChecker().localStates[1];
            		Integer state1 = (Integer)ls0.getValue("state");
            		Integer state2 = (Integer)ls1.getValue("state");
            		if(state1 != null && state2 != null) {
            			if((state1==1 && state2==2) || (state1==2 && state2==1))
            				enableNodeTwoEvent = true;
            		}
            		
            		Event event = ((PacketSendTransition) cloneTransition).getPacket();
                	if(event.getFromId() == 2 && !enableNodeTwoEvent) {
                		continue;
                	}
                	
                	if(((String)event.getValue(Event.FILENAME)).substring(0, 3).equals("rc-")) {
                		enableNodeTwoSync = true;
                	}
                	
                	if(((String)event.getValue(Event.FILENAME)).substring(0, 5).equals("sync-") 
                			&& (event.getFromId()==2 || event.getToId()==2) && !enableNodeTwoSync) {
                		continue;
                	}
                	cloneQueue.remove(i);
                }
            } else {
            	cloneTransition = cloneQueue.remove(i);
            } 	
        	
        	if(!exploredBranchRecorder.isSubtreeBelowChildFinished(cloneTransition.getTransitionId())) {
            	for(int j=0; j<transitions.size(); j++){
            		Transition transition = transitions.get(j);
            		if(cloneTransition.getTransitionId() == transition.getTransitionId()){
                        return transitions.remove(j);
            		}
            	}
            }
        }
        return null;
    }
    
    protected void recordTestId() {
        exploredBranchRecorder.noteThisNode(".test_id", testId + "");
    }
    
    protected void adjustCrashAndReboot(LinkedList<Transition> enabledTransitions) {
        int numOnline = 0;
        for (int i = 0; i < numNode; ++i) {
            if (isNodeOnline(i)) {
                numOnline++;
            }
        }
        int numOffline = numNode - numOnline;
        int tmp = numOnline < numCrash - currentCrash ? numOnline : numCrash - currentCrash;
        for (int i = 0; i < tmp; ++i) {
        	LOG.debug("DMCK add crash event");
            enabledTransitions.add(new AbstractNodeCrashTransition(this, true));
            currentCrash++;
            numOffline++;
        }
        tmp = numOffline < numReboot - currentReboot ? numOffline : numReboot - currentReboot;
        for (int i = 0; i < tmp; ++i) {
        	LOG.debug("DMCK add start event");
            enabledTransitions.add(new AbstractNodeStartTransition(this, true));
            currentReboot++;
        }
    }
    
    protected void removeCrashedSenderPackets(int crashedSender, LinkedList<Transition> enabledTransitions) {
        ListIterator<Transition> iter = enabledTransitions.listIterator();
        while (iter.hasNext()) {
            Transition t = iter.next();
            if (t instanceof PacketSendTransition) {
                if (((PacketSendTransition) t).getPacket().getFromId() == crashedSender) {
                    iter.remove();
                }
            }
        }
    }

    protected void saveNextTransition(String nextTransition) {
    	try {
            localRecordFile.write(("Next Execute: " + nextTransition + "\n").getBytes());
        } catch (IOException e) {
            LOG.error("", e);
        }
    }
    
    class PathTraversalWorker extends Thread {
        
        @Override
        public void run() {
        	boolean hasWaited = false;
            while (true) {
                updateSAMCQueue();
                boolean terminationPoint = checkTerminationPoint(currentEnabledTransitions);
                if (terminationPoint && hasWaited) {
                    boolean verifiedResult = verifier.verify();
                    String detail = verifier.verificationDetail();
                    saveResult(verifiedResult + " ; " + detail + "\n");
                    recordTestId();
                    exploredBranchRecorder.markBelowSubtreeFinished();
                    LOG.info("---- End of Path Execution ----");
                    resetTest();
                    break;
                } else if (terminationPoint) {
                	try {
                		if(interceptorName.equals("raftModelChecker") && waitForNextLE && waitedForNextLEInDiffTermCounter < 20){
	                        Thread.sleep(leaderElectionTimeout);
	                    } else {
	                    	hasWaited = true;
	                    	LOG.debug("Wait for any long process");
	                    	Thread.sleep(waitEndExploration);
	                    }
                    } catch (InterruptedException e) {
                    	e.printStackTrace();
                    }
                    continue;
                }
                hasWaited = false;
                Transition transition;
                // take next path based on initial path or current policy
                boolean recordPath = true;
                if(hasInitialPath && !hasFinishedInitialPath){
                	LOG.debug("DMCK next event execution is directed by initial path");
                	transition = nextInitialTransition();
                	recordPath = false;
                } else {
                	transition = nextTransition(currentEnabledTransitions);
                }
                if (transition != null) {
                	if (transition instanceof AbstractRaftSnapshot){
                    	if(!hasOneLeader()){
                    		// put the snapshot event back to queue if there is more than one Leader or none
                    		currentEnabledTransitions.add(transition);
                    		currentSnapshot++;
                    		continue;
                    	}
                    }
                	if(recordPath){
	                    exploredBranchRecorder.createChild(transition.getTransitionId());
	                    exploredBranchRecorder.traverseDownTo(transition.getTransitionId());
	                    exploredBranchRecorder.noteThisNode(".packets", transition.toString(), false);
                	}
                	// check next event execution
                	boolean cleanTransition = verifier.verifyNextTransition(transition);
                	if(!cleanTransition){
                		String detail = verifier.verificationDetail();
                        saveResult(cleanTransition + " ; " + detail + "\n");
                        recordTestId();
                        exploredBranchRecorder.markBelowSubtreeFinished();
                        LOG.warn("[NEXT TRANSITION] False next transition");
                    	LOG.info("---- End of Path Execution ----");
                        resetTest();
                        break;
                	}
                    try {
                        if (transition instanceof AbstractNodeOperationTransition) {
                            AbstractNodeOperationTransition nodeOperationTransition = (AbstractNodeOperationTransition) transition;
                            transition = ((AbstractNodeOperationTransition) transition).getRealNodeOperationTransition();
                            if (transition == null) {
                                currentEnabledTransitions.add(nodeOperationTransition);
                                continue;
                            }
                            nodeOperationTransition.id = ((NodeOperationTransition) transition).getId();
                        }
                    	saveNextTransition(transition.toString());
                    	LOG.info("[NEXT TRANSITION] " + transition.toString());
                        if (transition.apply()) {
                            pathRecordFile.write((transition.toString() + "\n").getBytes());
                            updateGlobalState();
                            updateSAMCQueueAfterEventExecution(transition);
                        }
                    } catch (IOException e) {
                        LOG.error("", e);
                    }
                } else if (exploredBranchRecorder.getCurrentDepth() == 0) {
                    LOG.warn("Finished exploring all states");
                } else {
                    try {
                        pathRecordFile.write("duplicated\n".getBytes());
                    } catch (IOException e) {
                        LOG.error("", e);
                    }
                    resetTest();
                    break;
                }
            }
        }

    }
}
