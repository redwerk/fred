package freenet.client.filter;

import java.io.*;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class TheoraPacketFilter implements CodecPacketFilter {
	static final byte[] magicNumber = new byte[] {'t', 'h', 'e', 'o', 'r', 'a'};
	enum Packet {IDENTIFICATION_HEADER, COMMENT_HEADER, SETUP_HEADER, SOME_PACKET, END_OF_PAGE}
	private Packet expectedPacket = Packet.IDENTIFICATION_HEADER;
	private boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
	private int somePacketCounter = 0;

	public CodecPacket parse(CodecPacket packet) throws IOException {
		// Assemble the Theora packets https://www.theora.org/doc/Theora.pdf
		DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet.payload));
		byte[] magicHeader = new byte[1 + magicNumber.length];
		short unalignedBytes;
		try {
			switch(expectedPacket) {
				case IDENTIFICATION_HEADER: // must be first
					if (logMINOR)
						Logger.minor(this, "IDENTIFICATION_HEADER");

					// The header packets begin with the header type and the magic number. Validate both.
					input.readFully(magicHeader);
					checkMagicHeader(magicHeader, (byte) 0x80); // -128

					// Assemble identification header
					int VMAJ = read8bit(input);
					int VMIN = read8bit(input);
					int VREV = read8bit(input);
					int FMBW = read16bit(input);
					int FMBH = read16bit(input);
					int PICW = read24bit(input);
					int PICH = read24bit(input);
					int PICX = read8bit(input);
					int PICY = read8bit(input);
					int FRN = read32bit(input);
					int FRD = read32bit(input);
					int PARN = read24bit(input);
					int PARD = read24bit(input);
					int CS = read8bit(input);
					int NOMBR = read24bit(input);

					unalignedBytes = input.readShort();
					byte QUAL = (byte) (unalignedBytes & 0x3f); // 6 bit 0b111111
					byte KFGSHIFT = (byte) (unalignedBytes & 0x7C0); // 5 bit 0b11111000000
					byte PF = (byte) (unalignedBytes & 0x1800); // 2 bit 0b1100000000000
					byte Res = (byte) (unalignedBytes & 0xE000); // 3 bit 0b1110000000000000

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

					int vendorLength = decode32bitIntegerFrom8BitChunks(input);
					byte[] vendor = new byte[vendorLength];
					input.readFully(vendor);
					if (logMINOR)
						Logger.minor(this, "Vendor string is: " + new String(vendor));
					int numberOfComments = decode32bitIntegerFrom8BitChunks(input);
					for (long i = 0; i < numberOfComments; i++) {
						int commentLength = decode32bitIntegerFrom8BitChunks(input);
						byte[] comment = new byte[commentLength];
						input.readFully(comment);
						if (logMINOR)
							Logger.minor(this, "Comment string is: " + new String(comment));
					}
					if (logMINOR)
						Logger.minor(this, "COMMENT_HEADER contains " + input.available() + " redundant bytes");

					// skip vendor string and comment
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

	private int read8bit(DataInputStream input) throws IOException {
		return input.readUnsignedByte();
	}

	private int read16bit(DataInputStream input) throws IOException {
		return input.readShort();
	}

	private int read24bit(DataInputStream input) throws IOException {
		return input.readShort() << 8 | input.readUnsignedByte();
	}

	private int read32bit(DataInputStream input) throws IOException {
		return input.readInt();
	}

	private int decode32bitIntegerFrom8BitChunks(DataInputStream input) throws IOException {
		int LEN0 = input.readUnsignedByte();
		int LEN1 = input.readUnsignedByte();
		int LEN2 = input.readUnsignedByte();
		int LEN3 = input.readUnsignedByte();
		return LEN0|(LEN1 << 8)|(LEN2 << 16)|(LEN3 << 24);
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
