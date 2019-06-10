
public class Token extends Packet {
	int tokenID;
	
	Token(int srcID, int tokenID) {
		super.type = 30;
		super.srcID = srcID;
		this.tokenID = tokenID;
	}
}
