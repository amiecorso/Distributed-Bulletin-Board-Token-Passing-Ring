
public class Message implements Comparable<Message> {
	int sendtime;
	int minutes;
	int seconds;
	String body;
	int timesent;
	int seqno;
	
	Message (int minutes, int seconds, String body, int seqno) {
		this.minutes = minutes;
		this.seconds = seconds;
		this.sendtime = 60 * minutes + seconds;
		this.body = body;
		this.seqno = seqno;
	}

	@Override
	public int compareTo(Message o) {
		return this.sendtime - o.sendtime;
	}
	
	public String toString() {
		return Integer.toString(minutes) + ":" + Integer.toString(seconds) + "\t" + this.body;
	}
}
