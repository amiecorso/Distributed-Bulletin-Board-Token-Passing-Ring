
public class Probe extends Packet {
	int msgID;
	
	Probe(int srcID, int msgID) {
		super.type = 10;
		super.srcID = srcID;
		this.msgID = msgID;
	}
}
