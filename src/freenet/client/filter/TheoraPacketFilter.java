package freenet.client.filter;

import java.io.*;
import java.nio.ByteOrder;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.BitInputStream;

public class TheoraPacketFilter implements CodecPacketFilter {
	static final byte[] magicNumber = new byte[] {'t', 'h', 'e', 'o', 'r', 'a'};
	enum Packet {IDENTIFICATION_HEADER, COMMENT_HEADER, SETUP_HEADER, SOME_PACKET, END_OF_PAGE}
	private Packet expectedPacket = Packet.IDENTIFICATION_HEADER;
	private boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
	private int somePacketCounter = 0;

	public CodecPacket parse(CodecPacket packet) throws IOException {
		// Assemble the Theora packets https://www.theora.org/doc/Theora.pdf
		BitInputStream input = new BitInputStream(new ByteArrayInputStream(packet.payload));
		byte[] magicHeader = new byte[1 + magicNumber.length];
		try {
			switch(expectedPacket) {
				case IDENTIFICATION_HEADER: // must be first
					if (logMINOR)
						Logger.minor(this, "IDENTIFICATION_HEADER");

					// The header packets begin with the header type and the magic number. Validate both.
					input.readFully(magicHeader);
					checkMagicHeader(magicHeader, (byte) 0x80); // -128

					// Assemble identification header
					int VMAJ = input.readInt(8);
					int VMIN = input.readInt(8);
					int VREV = input.readInt(8);
					int FMBW = input.readInt(16);
					int FMBH = input.readInt(16);
					int PICW = input.readInt(24);
					int PICH = input.readInt(24);
					int PICX = input.readInt(8);
					int PICY = input.readInt(8);
					int FRN = input.readInt(32);
					int FRD = input.readInt(32);
					int PARN = input.readInt(24);
					int PARD = input.readInt(24);
					int CS = input.readInt(8);
					int NOMBR = input.readInt(24);
					int QUAL = input.readInt(6);
					int KFGSHIFT = input.readInt(5);
					int PF = input.readInt(2);
					int Res = input.readInt(3);

					if (VMAJ != 3) throw new UnknownContentTypeException("Header VMAJ: " + VMAJ);
					if (VMIN != 2) throw new UnknownContentTypeException("Header VMIN: " + VMIN);
					if (VREV > 1) throw new UnknownContentTypeException("Header VREV: " + VREV);
					if (FMBW == 0) throw new UnknownContentTypeException("Header FMBW: " + FMBW);
					if (FMBH == 0) throw new UnknownContentTypeException("Header FMBH: " + FMBH);
					if (PICW > FMBW*16) throw new UnknownContentTypeException("Header PICW: " + PICW + "; FMBW: " + FMBW);
					if (PICH > FMBH*16) throw new UnknownContentTypeException("Header PICH: " + PICH + "; FMBH: " + FMBH);
					if (PICX > FMBW*16-PICX) throw new UnknownContentTypeException("Header PICX: " + PICX + "; FMBW: " + FMBW + "; PICX: " + PICX);
					if (PICY > FMBH*16-PICY) throw new UnknownContentTypeException("Header PICY: " + PICY + "; FMBH: " + FMBH + "; PICY: " + PICY);
					if (FRN == 0) throw new UnknownContentTypeException("Header FRN: " + FRN);
					if (FRD == 0) throw new UnknownContentTypeException("Header FRN: " + FRN);
					if (!(CS == 0 || CS == 1 || CS == 2)) throw new UnknownContentTypeException("Header CS: " + CS);
					if (PF == 1) throw new UnknownContentTypeException("Header PF: " + PF);
					if (Res != 0) throw new UnknownContentTypeException("Header Res: " + Res);

					expectedPacket = Packet.COMMENT_HEADER;
					break;

				case COMMENT_HEADER: // must be second
					if (logMINOR)
						Logger.minor(this, "COMMENT_HEADER");

					input.readFully(magicHeader);
					checkMagicHeader(magicHeader, (byte) 0x81); // -127

					int vendorLength = input.readInt(32, ByteOrder.LITTLE_ENDIAN);
					byte[] vendor = new byte[vendorLength];
					input.readFully(vendor);
					if (logMINOR)
						Logger.minor(this, "Vendor string is: " + new String(vendor));
					int numberOfComments = input.readInt(32, ByteOrder.LITTLE_ENDIAN);
					for (long i = 0; i < numberOfComments; i++) {
						int commentLength = input.readInt(32, ByteOrder.LITTLE_ENDIAN);
						byte[] comment = new byte[commentLength];
						input.readFully(comment);
						if (logMINOR)
							Logger.minor(this, "Comment string is: " + new String(comment));
					}
					if (logMINOR)
						Logger.minor(this, "COMMENT_HEADER contains " + input.available() + " redundant bytes");

					// skip vendor string and comments
					try (ByteArrayOutputStream data = new ByteArrayOutputStream();
						 DataOutputStream output = new DataOutputStream(data)) {
						output.write(magicHeader);
						output.writeLong(0);
						packet = new CodecPacket(data.toByteArray());
					}

					expectedPacket = Packet.SETUP_HEADER;
					break;

				case SETUP_HEADER: // must be third
					if (logMINOR)
						Logger.minor(this, "SETUP_HEADER");

					input.readFully(magicHeader);
					checkMagicHeader(magicHeader, (byte) 0x82); // -126

					// TODO: end of page 50
					if (logMINOR)
						Logger.minor(this, "SETUP_HEADER: available " + input.available() + " bytes");

					expectedPacket = Packet.SOME_PACKET;
					break;

				case SOME_PACKET:
					if (logMINOR)
						Logger.minor(this, "SOME_PACKET " + ++somePacketCounter + ": available " + input.available());
					break;

				case END_OF_PAGE:
					throw new DataFilterException("End of page");
			}
		} catch(IOException e) {
			if (logMINOR) Logger.minor(this, "In Theora parser caught " + e, e);
			throw e;
		}

		return packet;
	}

	private void checkMagicHeader(byte[] typeAndMagicHeader, byte expectedType) throws IOException {
		if (logMINOR) Logger.minor(this, "Header type: " + typeAndMagicHeader[0]);

		if (typeAndMagicHeader[0] != expectedType)
			throw new UnknownContentTypeException("First header type: " + typeAndMagicHeader[0] + ", expected: " + expectedType);

		for (int i=0; i < magicNumber.length; i++) {
			if (typeAndMagicHeader[i+1] != magicNumber[i])
				throw new UnknownContentTypeException("Packet header magicNumber[" + i + "]: " + typeAndMagicHeader[i+1]);
		}
	}
}
