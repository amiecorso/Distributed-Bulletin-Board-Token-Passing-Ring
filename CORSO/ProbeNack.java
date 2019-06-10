
public class ProbeNack extends Packet {
	int srcID;
	int msgID;
	
	ProbeNack(int srcID, int msgID) {
		super.type = 12;
		super.srcID = srcID;
		this.msgID = msgID;
	}
}
