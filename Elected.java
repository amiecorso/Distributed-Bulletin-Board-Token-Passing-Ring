
public class Elected extends Packet {
	int electionID;
	int electedClient;
	
	Elected(int srcID, int electionID, int electedClient) {
		super.type = 21;
		super.srcID = srcID;
		this.electionID = electionID;
		this.electedClient = electedClient;
	}
}
