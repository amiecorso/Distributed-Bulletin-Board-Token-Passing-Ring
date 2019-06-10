
public class ProbeAck extends Packet {
	int msgID;
	
	ProbeAck(int srcID, int msgID) {
		super.type = 11;
		super.srcID = srcID;
		this.msgID = msgID;
	}
}
