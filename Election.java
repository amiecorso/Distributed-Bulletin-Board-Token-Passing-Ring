
public class Election extends Packet {
	int electionID; // client who initiated?
	int bestSoFar;
	
	Election(int srcID, int electionID, int bestSoFar) {
		super.type = 20;
		super.srcID = srcID;
		this.electionID = electionID;
		this.bestSoFar = bestSoFar;
	}
}
