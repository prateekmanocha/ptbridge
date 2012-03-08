package at.andiwand.packettracer.bridge.traverser.translator;

import java.util.HashMap;
import java.util.Map;

import at.andiwand.library.network.Assignments;
import at.andiwand.packetsocket.pdu.PDU;
import at.andiwand.packetsocket.pdu.TCPSegment;
import at.andiwand.packetsocket.pdu.TelnetSegment;
import at.andiwand.packetsocket.pdu.formatter.PDUFormatterFactory;
import at.andiwand.packettracer.bridge.ptmp.PTMPEncoding;
import at.andiwand.packettracer.bridge.ptmp.multiuser.pdu.MultiuserPDU;
import at.andiwand.packettracer.bridge.ptmp.multiuser.pdu.MultiuserTCPSegment;
import at.andiwand.packettracer.bridge.ptmp.multiuser.pdu.MultiuserTelnetSegment;


public class TCPTranslator extends
		GenericPDUTranslator<TCPSegment, MultiuserTCPSegment> {
	
	private static enum Direction {
		NETWORK,
		PACKET_TRACER;
	}
	
	private static class ConnectionSlot {
		private final int sourcePort;
		private final int destinationPort;
		private final Direction direction;
		
		public ConnectionSlot(int sourcePort, int destinationPort,
				Direction direction) {
			this.sourcePort = sourcePort;
			this.destinationPort = destinationPort;
			this.direction = direction;
		}
		
		@Override
		public String toString() {
			return "[(" + sourcePort + ", " + destinationPort + ") to "
					+ direction + "]";
		}
		
		@Override
		public boolean equals(Object obj) {
			if (obj == null) return false;
			if (obj == this) return true;
			
			if (!(obj instanceof ConnectionSlot)) return false;
			ConnectionSlot connection = (ConnectionSlot) obj;
			
			return ((sourcePort == connection.sourcePort) && (destinationPort == connection.destinationPort))
					|| ((sourcePort == connection.destinationPort) && (destinationPort == connection.sourcePort));
		}
		
		@Override
		public int hashCode() {
			return sourcePort ^ destinationPort;
		}
	}
	
	// TODO: implement dictionary
	// TODO: auto clean
	// TODO: port direction?!
	// TODO: kill ack offset - implement segment size offset
	// TODO: open connection translation?
	private static class SequenceTranslator {
		private final Map<Long, Long> toMultiuserMap = new HashMap<Long, Long>();
		private final Map<Long, Long> toNetworkMap = new HashMap<Long, Long>();
		
		private long multiuserAckExpacted;
		private long multiuserAckDefect = -1;
		private long multiuserAckDefectOffset;
		
		@Override
		public String toString() {
			return toMultiuserMap.toString();
		}
		
		private void putTranslation(long networkSequence, long multiuserSequence) {
			toMultiuserMap.put(networkSequence, multiuserSequence);
			toNetworkMap.put(multiuserSequence, networkSequence);
			
			if (multiuserSequence > multiuserAckExpacted)
				multiuserAckExpacted = multiuserSequence;
		}
		
		private Long getToMultiuser(long number) {
			Long result = toMultiuserMap.get(number);
			if (result == null) return null;
			if ((multiuserAckDefect != -1) && (result >= multiuserAckDefect))
				result += multiuserAckDefectOffset;
			return result;
		}
		
		private Long getToNetwork(long number) {
			if ((multiuserAckDefect != -1) && (number >= multiuserAckDefect))
				number -= multiuserAckDefectOffset;
			return toNetworkMap.get(number);
		}
		
		public long seqToMultiuser(TCPSegment segment,
				MultiuserPDU multiuserPayload) {
			long seq = segment.getSequenceNumber();
			
			Long result = getToMultiuser(seq);
			if (segment.getFlags() == Assignments.TCP.FLAG_ACK) return result;
			
			if (result == null) {
				result = seq;
				putTranslation(seq, seq);
			}
			
			PDU networkPayload = segment.getPayload();
			int networkPayloadSize = 1;
			int multiuserPayloadSize = 1;
			
			if (networkPayload != null) {
				networkPayloadSize = PDUFormatterFactory.FACTORY
						.getFormatterInstance(networkPayload.getClass())
						.format(networkPayload).length;
				multiuserPayloadSize = "CTelnetPacket\0".length()
						+ multiuserPayload.getBytes(PTMPEncoding.BINARY).length;
			}
			
			putTranslation(seq + networkPayloadSize, result
					+ multiuserPayloadSize);
			
			return result;
		}
		
		public long seqToNetwork(PDU networkPayload, MultiuserTCPSegment segment) {
			long seq = segment.getSequenceNumber();
			
			Long result = getToNetwork(seq);
			if (segment.getFlags() == Assignments.TCP.FLAG_ACK) return result;
			
			if (result == null) {
				result = seq;
				putTranslation(seq, seq);
			}
			
			MultiuserPDU multiuserPayload = segment.getPayload();
			int networkPayloadSize = 1;
			int multiuserPayloadSize = 1;
			
			if (networkPayload != null) {
				networkPayloadSize = PDUFormatterFactory.FACTORY
						.getFormatterInstance(networkPayload.getClass())
						.format(networkPayload).length;
				multiuserPayloadSize = "CTelnetPacket\0".length()
						+ multiuserPayload.getBytes(PTMPEncoding.BINARY).length;
			}
			
			putTranslation(result + networkPayloadSize, seq
					+ multiuserPayloadSize);
			
			return result;
		}
		
		public long ackToMultiuser(long ack) {
			return getToMultiuser(ack);
		}
		
		public long ackToNetwork(long ack) {
			if (ack > multiuserAckExpacted) {
				multiuserAckDefect = multiuserAckExpacted;
				multiuserAckDefectOffset = ack - multiuserAckExpacted;
			}
			
			return getToNetwork(ack);
		}
	}
	
	private static final TranslationHelper TRANSLATION_ASSOCIATOR = new TranslationHelper() {
		{
			putTranslator(TelnetSegment.class, MultiuserTelnetSegment.class,
					TelnetTranslator.class);
		}
	};
	
	private final Map<ConnectionSlot, SequenceTranslator> sequenceTranslatorMap = new HashMap<ConnectionSlot, SequenceTranslator>();
	
	// TODO: complete
	@Override
	protected TCPSegment toNetworkGeneric(MultiuserTCPSegment segment) {
		TCPSegment result = new TCPSegment();
		
		// TODO: remove
		int minPort = Math.min(segment.getSourcePort(), segment
				.getDestinationPort());
		if (minPort != 23) throw new RuntimeException();
		
		result.setSourcePort(segment.getSourcePort());
		result.setDestinationPort(segment.getDestinationPort());
		result.setReserved((byte) 0);
		result.setFlags(segment.getFlags());
		result.setWindow(14600);
		result.setChecksum((short) 0);
		result.setUrgentPointer(0);
		
		if (segment.getPayload() != null) {
			Class<?> payloadClass = segment.getPayload().getClass();
			PDUTranslator payloadTranslator = TRANSLATION_ASSOCIATOR
					.getTranslator(payloadClass);
			PDU payload = payloadTranslator.toNetwork(segment.getPayload());
			result.setPayload(payload);
		} else {
			result.setPayload(null);
		}
		
		ConnectionSlot connectionSlot = new ConnectionSlot(segment
				.getSourcePort(), segment.getDestinationPort(),
				Direction.NETWORK);
		SequenceTranslator sequenceTranslator = sequenceTranslatorMap
				.get(connectionSlot);
		
		if (sequenceTranslator == null) {
			sequenceTranslator = new SequenceTranslator();
			sequenceTranslatorMap.put(connectionSlot, sequenceTranslator);
		}
		
		result.setSequenceNumber(sequenceTranslator.seqToNetwork(result
				.getPayload(), segment));
		if ((segment.getFlags() & Assignments.TCP.FLAG_ACK) != 0) {
			result.setAcknowledgmentNumber(sequenceTranslator
					.ackToNetwork(segment.getAcknowledgmentNumber()));
		}
		
		return result;
	}
	
	// TODO: complete
	@Override
	protected MultiuserTCPSegment toMultiuserGeneric(TCPSegment segment) {
		MultiuserTCPSegment result = new MultiuserTCPSegment();
		
		if (segment.getPayload() != null) {
			Class<?> payloadClass = segment.getPayload().getClass();
			PDUTranslator payloadTranslator = TRANSLATION_ASSOCIATOR
					.getTranslator(payloadClass);
			MultiuserPDU payload = payloadTranslator.toMultiuser(segment
					.getPayload());
			result.setPayload(payload);
		} else {
			result.setPayload(null);
		}
		
		// TODO: remove
		int minPort = Math.min(segment.getSourcePort(), segment
				.getDestinationPort());
		if (minPort != 23) throw new RuntimeException("Unsupported port!");
		
		result.setSourcePort(segment.getSourcePort());
		result.setDestinationPort(segment.getDestinationPort());
		result.setUnknown2(0);
		result.setUnknown3((byte) 0);
		result.setUnknown4((byte) 15);
		result.setFlags(segment.getFlags());
		result.setUnknown5((short) -1);
		result.setUnknown6((short) 0);
		result.setUnknown7((byte) 0);
		result.setUnknown8(0);
		
		ConnectionSlot connectionSlot = new ConnectionSlot(segment
				.getSourcePort(), segment.getDestinationPort(),
				Direction.NETWORK);
		SequenceTranslator sequenceTranslator = sequenceTranslatorMap
				.get(connectionSlot);
		
		if (sequenceTranslator == null) {
			sequenceTranslator = new SequenceTranslator();
			sequenceTranslatorMap.put(connectionSlot, sequenceTranslator);
		}
		
		result.setSequenceNumber(sequenceTranslator.seqToMultiuser(segment,
				result.getPayload()));
		if ((segment.getFlags() & Assignments.TCP.FLAG_ACK) != 0) {
			result.setAcknowledgmentNumber(sequenceTranslator
					.ackToMultiuser(segment.getAcknowledgmentNumber()));
		}
		
		return result;
	}
}