package algs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.graph.SimpleWeightedGraph;

import graph.Node;
import simulation.Parameters;
import simulation.SDNRoutingSimulator;
import system.DataCenter;
import system.InternetLink;
import system.Request;
import system.Switch;
import utils.HPair;
import utils.HTriple;

public class Online {

	private SDNRoutingSimulator simulator = null;
	
	private ArrayList<Request> requests = null; 
	
	private Map<Integer, ArrayList<Request>> requestsType = new HashMap<Integer, ArrayList<Request>>();
	
	private double totalCost = 0d;
	
	private double averageCost = 0d; 
	
	private int numOfAdmittedReqs = 0;
	
	// dual variables, initialized to zeros. 
	
	private double [][] beta = null; // k, j
	
	private double [][][] mu = null; // i, k, j
	
	private double theta;
	
	private double [][] lambda = null;// i, k 
	
	private double epsilon = 0.2;// (0, 1/3]
	
	private double budget = 0d;
		
	public Online (SDNRoutingSimulator sim, ArrayList<Request> requests, double budgetScaleFactor) {
		this.setSimulator(sim);	
		this.setRequests(requests);
		
		for (Request req : requests) {
			if (null == this.requestsType.get(req.getServiceChainType()))
				this.requestsType.put(req.getServiceChainType(), new ArrayList<Request>());
			this.requestsType.get(req.getServiceChainType()).add(req);
		}
		
		// initialize arrays for beta, mu, lambda. 
		for (int k = 0; k < Parameters.K; k ++) {
			for (int j = 0; j < this.requestsType.get(k).size(); j ++) {
				this.beta[k][j] = 0d;
			}
		}
		for (int i = 0; i < this.getSimulator().getSwitchesAttachedDataCenters().size(); i++){
			for (int k = 0; k < Parameters.K; k ++) {
				for (int j = 0; j < this.requestsType.get(k).size(); j ++) {
					this.mu[i][k][j] = 0d;
				}
			}
		}
		for (int i = 0; i < this.getSimulator().getSwitchesAttachedDataCenters().size(); i++){
			for (int k = 0; k < Parameters.K; k ++) {
				this.lambda[i][k] = 0d; 
			}
		}
		
		this.theta = 0d; 
		
		//TODO: adjust or refine the budget calculation
		this.budget = (Parameters.maxLinkCost * (this.simulator.getNetwork().vertexSet().size() - 1) + Parameters.maxServiceChainCost) * Parameters.maxPacketRate * budgetScaleFactor;
	}
	
	public void run() {
		// online algorithm based on primal-dual approach.
		
		Map<DataCenter, Double> shadowPrices = new HashMap<DataCenter, Double>();
		for (Switch swDC : this.simulator.getSwitchesAttachedDataCenters()){
			DataCenter dc = swDC.getAttachedDataCenter();
			shadowPrices.put(dc, 0d);
		}
		
		double Delta = calculateDelta();
		
		for (Request request : this.getRequests()) {
			// find the data center with the minimum shadow price. 
			HTriple<ArrayList<DataCenter>, Map<DataCenter, Double>, Map<DataCenter, Double>> retTripleDCListDelays = getDataCentersMeetDelayRequirement(request);
			ArrayList<DataCenter> dcsMeetDelay = retTripleDCListDelays.getA();
			Map<DataCenter, Double> delaysForThisReq = retTripleDCListDelays.getB();
			Map<DataCenter, Double> costsForThisReq = retTripleDCListDelays.getC();
			
			// get dc with minimum shadow price
			HPair<DataCenter, Double> retPairShadowPriceDCList = minShadowPriceDataCenter(shadowPrices, dcsMeetDelay);
			DataCenter minShadowPriceDC = retPairShadowPriceDCList.getA();
			Double minShadowPrice = retPairShadowPriceDCList.getB();
			
			double threshold = Math.pow(delaysForThisReq.get(minShadowPriceDC), 2)/(Delta * request.getDelayRequirement() * request.getPacketRate());
			threshold = 1 - threshold; 
			assert(threshold < 0 ) : "threshold should be positive";
			
			if (minShadowPrice <= threshold) {
				// accept this request 
				this.numOfAdmittedReqs ++;
				this.totalCost += costsForThisReq.get(minShadowPriceDC);
				// update dual variables
				updateDualVariables(request, minShadowPriceDC, Delta, delaysForThisReq.get(minShadowPriceDC), costsForThisReq.get(minShadowPriceDC));
			}
		}
	}
	
	private void updateDualVariables(Request request, DataCenter dc, Double Delta, Double delay, Double cost) {
		
		// lambda
		double totalProcessingRateType = dc.getProcessingRateCapacityType(request.getServiceChainType()); 
		 
		int i = 0;
		for (; i < this.simulator.getSwitchesAttachedDataCenters().size(); i ++) {
			if (this.simulator.getSwitchesAttachedDataCenters().get(i).equals(dc))
				break;
		}
		int k = request.getServiceChainType();
		
		int j = 0;
		for (; j < this.requestsType.get(k).size(); j ++){
			if (this.requestsType.get(k).get(j).equals(request))
				break;
		}
		
		this.lambda[i][k] = this.lambda[i][k] * (1d + request.getPacketRate()/totalProcessingRateType) + request.getPacketRate() / (Delta * totalProcessingRateType);
		this.mu[i][k][j] = delay/(Delta * request.getDelayRequirement());
		this.theta = this.theta * (1 + request.getPacketRate() * cost / this.budget) + (request.getPacketRate() * cost)/(Delta * this.budget);
		this.beta[k][j] = request.getPacketRate() * (1 - this.lambda[i][k] - this.theta * cost);
	}
	
	private double calculateDelta() {
		
		double maxDelayRhoRatio = -1; 
		double maxCost = -1; 
		for (Request request : this.getRequests()) {
			// find the data center with the minimum shadow price. 
			HTriple<ArrayList<DataCenter>, Map<DataCenter, Double>, Map<DataCenter, Double>> retTripleDCListDelays = getDataCentersMeetDelayRequirement(request);
			ArrayList<DataCenter> dcsMeetDelay = retTripleDCListDelays.getA();
			Map<DataCenter, Double> delaysForThisReq = retTripleDCListDelays.getB();
			Map<DataCenter, Double> costsForThisReq = retTripleDCListDelays.getC();
			
			for (DataCenter dc : dcsMeetDelay){
				double delayRhoRatio = delaysForThisReq.get(dc)/request.getPacketRate();
				double costDCReq = costsForThisReq.get(dc);
				if (maxDelayRhoRatio < delayRhoRatio){
					maxDelayRhoRatio = delayRhoRatio;
				}
				if (maxCost < costDCReq){
					maxCost = costDCReq; 
				}
			}			
		}
		double temp = (maxDelayRhoRatio > maxCost)? maxDelayRhoRatio: maxCost;
		
		double I_star = (temp > 1d)? temp : 1d; 
		
		return I_star/this.epsilon;
	}
		
	// map1: data center --> delay, map2: data center --> cost
	private HTriple<ArrayList<DataCenter>, Map<DataCenter, Double>, Map<DataCenter, Double>> getDataCentersMeetDelayRequirement(Request req) {
		
		ArrayList<DataCenter> retDCS = new ArrayList<DataCenter>();
		Map<DataCenter, Double> DCDelays = new HashMap<DataCenter, Double>();
		Map<DataCenter, Double> DCCosts = new HashMap<DataCenter, Double>();
		
		SimpleWeightedGraph<Node, InternetLink> originalGraph = this.simulator.getNetwork();
		
		for (Switch swDC : this.simulator.getSwitchesAttachedDataCenters()){
			DataCenter dc = swDC.getAttachedDataCenter();
			
			Node sourceSwitch = req.getSourceSwitch();
			Node destSwitch = req.getDestinationSwitches().get(0);
			
			DijkstraShortestPath<Node, InternetLink> shortestPathSToDC = new DijkstraShortestPath<Node, InternetLink>(originalGraph, sourceSwitch, dc);
			double delay1 = Double.MAX_VALUE; 
			double pathCost1 = Double.MAX_VALUE;
			for (int i = 0; i < shortestPathSToDC.getPathEdgeList().size(); i ++) {
				if (0 == i ) {
					delay1 = 0d;
					pathCost1 = 0d;
				}
				delay1 += shortestPathSToDC.getPathEdgeList().get(i).getLinkDelay();
				pathCost1 += originalGraph.getEdgeWeight(shortestPathSToDC.getPathEdgeList().get(i));
			}
			
			DijkstraShortestPath<Node, InternetLink> shortestPathDCToDest = new DijkstraShortestPath<Node, InternetLink>(originalGraph, dc, destSwitch);
			double delay2 = Double.MAX_VALUE; 
			double pathCost2 = Double.MAX_VALUE;
			for (int i = 0; i < shortestPathDCToDest.getPathEdgeList().size(); i ++) {
				if (0 == i ) {
					delay2 = 0d;
					pathCost2 = 0d; 
				}
				delay2 += shortestPathDCToDest.getPathEdgeList().get(i).getLinkDelay();
				pathCost2 += originalGraph.getEdgeWeight(shortestPathDCToDest.getPathEdgeList().get(i));
			}
			
			double delay = delay1 + delay2 + dc.getProcessingDelays()[req.getServiceChainType()];
			double cost = req.getPacketRate() * (pathCost1 + pathCost2 + dc.getCosts()[req.getServiceChainType()]);
			if (delay < req.getDelayRequirement()) {
				retDCS.add(dc); 
				DCDelays.put(dc, delay);
				DCCosts.put(dc, cost);
			}
		}
		
		return new HTriple<ArrayList<DataCenter>, Map<DataCenter, Double>, Map<DataCenter, Double>>(retDCS, DCDelays, DCCosts);
	}
	
	private HPair<DataCenter, Double> minShadowPriceDataCenter(Map<DataCenter, Double> shadowPrices, ArrayList<DataCenter> datacentersMeetDelay){
		
		double minShadowPrice = Double.MAX_VALUE;
		DataCenter minShadowPriceDC = null;
		
		for (DataCenter dc : datacentersMeetDelay){
			if (shadowPrices.get(dc) < minShadowPrice) {
				minShadowPrice = shadowPrices.get(dc);
				minShadowPriceDC = dc;
			}
		}
		
		return new HPair<DataCenter, Double>(minShadowPriceDC, minShadowPrice);
	}

	public SDNRoutingSimulator getSimulator() {
		return simulator;
	}

	public void setSimulator(SDNRoutingSimulator simulator) {
		this.simulator = simulator;
	}

	public ArrayList<Request> getRequests() {
		return requests;
	}

	public void setRequests(ArrayList<Request> requests) {
		this.requests = requests;
	}

	public double getTotalCost() {
		return totalCost;
	}

	public void setTotalCost(double totalCost) {
		this.totalCost = totalCost;
	}

	public double getAverageCost() {
		return averageCost;
	}

	public void setAverageCost(double averageCost) {
		this.averageCost = averageCost;
	}

	public int getNumOfAdmittedReqs() {
		return numOfAdmittedReqs;
	}

	public void setNumOfAdmittedReqs(int numOfAdmittedReqs) {
		this.numOfAdmittedReqs = numOfAdmittedReqs;
	}
}
