package freenet.client.filter;

import java.io.*;
import java.nio.ByteOrder;
import java.util.*;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.BitInputStream;

public class TheoraPacketFilter implements CodecPacketFilter {
	static final byte[] magicNumber = new byte[] {'t', 'h', 'e', 'o', 'r', 'a'};
	enum Packet {
		IDENTIFICATION_HEADER, COMMENT_HEADER, SETUP_HEADER,
		INTRA_FRAME, INTER_FRAME
	}
	private Packet expectedPacket = Packet.IDENTIFICATION_HEADER;
	private boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);

	public CodecPacket parse(CodecPacket packet) throws IOException {
		// Assemble the Theora packets https://www.theora.org/doc/Theora.pdf
		// https://github.com/xiph/theora/blob/master/doc/spec/spec.tex
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

					int VMAJ = input.readInt(8);
					if (VMAJ != 3) throw new UnknownContentTypeException("Header VMAJ: " + VMAJ);

					int VMIN = input.readInt(8);
					if (VMIN != 2) throw new UnknownContentTypeException("Header VMIN: " + VMIN);

					int VREV = input.readInt(8);
					if (VREV > 1) throw new UnknownContentTypeException("Header VREV: " + VREV);

					int FMBW = input.readInt(16);
					if (FMBW == 0) throw new UnknownContentTypeException("Header FMBW: " + FMBW);

					int FMBH = input.readInt(16);
					if (FMBH == 0) throw new UnknownContentTypeException("Header FMBH: " + FMBH);

					int PICW = input.readInt(24);
					if (PICW > FMBW*16) throw new UnknownContentTypeException("Header PICW: " + PICW + "; FMBW: " + FMBW);

					int PICH = input.readInt(24);
					if (PICH > FMBH*16) throw new UnknownContentTypeException("Header PICH: " + PICH + "; FMBH: " + FMBH);

					int PICX = input.readInt(8);
					if (PICX > FMBW*16-PICX) throw new UnknownContentTypeException("Header PICX: " + PICX + "; FMBW: " + FMBW + "; PICX: " + PICX);

					int PICY = input.readInt(8);
					if (PICY > FMBH*16-PICY) throw new UnknownContentTypeException("Header PICY: " + PICY + "; FMBH: " + FMBH + "; PICY: " + PICY);

					int FRN = input.readInt(32);
					if (FRN == 0) throw new UnknownContentTypeException("Header FRN: " + FRN);

					int FRD = input.readInt(32);
					if (FRD == 0) throw new UnknownContentTypeException("Header FRN: " + FRN);

					int PARN = input.readInt(24);
					int PARD = input.readInt(24);

					int CS = input.readInt(8);
					if (!(CS == 0 || CS == 1 || CS == 2)) throw new UnknownContentTypeException("Header CS: " + CS);

					int NOMBR = input.readInt(24);
					int QUAL = input.readInt(6);
					int KFGSHIFT = input.readInt(5);

					int PF = input.readInt(2);
					if (PF == 1) throw new UnknownContentTypeException("Header PF: " + PF);

					int Res = input.readInt(3);
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

					if (logMINOR)
						Logger.minor(this, "SETUP_HEADER: available " + input.available() + " bytes");

					int NBITS = input.readInt(3);
					int[] LFLIMS = new int[64];
					for (int i = 0; i < LFLIMS.length; i++)
						LFLIMS[i] = input.readInt(NBITS);

					NBITS = input.readInt(4) + 1;
					int[] ACSCALE = new int[64];
					for (int i = 0; i < ACSCALE.length; i++)
						ACSCALE[i] = input.readInt(NBITS);

					NBITS = input.readInt(4) + 1;
					int[] DCSCALE = new int[64];
					for (int i = 0; i < DCSCALE.length; i++)
						DCSCALE[i] = input.readInt(NBITS);

					int NBMS = input.readInt(9) + 1;
					if (NBMS > 384)
						throw new UnknownContentTypeException("SETUP HEADER - NBMS: " + NBMS + "(MUST be no greater than 384)");

					int[][] BMS = new int[NBMS][64];
					for (int i = 0; i < BMS.length; i++)
						for (int j = 0; j < BMS[i].length; j++)
							BMS[i][j] = input.readInt(8);

					for (int qti = 0; qti <= 1; qti++) {
						for (int pli = 0; pli <= 2; pli++) {
							int NEWQR = 1;
							if (qti > 0 || pli > 0)
								NEWQR = input.readBit();

							int[][] NQRS = new int[2][3];
							int[][][] QRSIZES = new int[2][3][63];
							int[][][] QRBMIS = new int[2][3][64];
							if (NEWQR == 0) {
								int qtj, plj;
								int RPQR = 0;
								if (qti > 0)
									RPQR = input.readBit();

								if (RPQR == 1) {
									qtj = qti - 1;
									plj = pli;
								}
								else {
									qtj = (3 * qti + pli - 1) / 3;
									plj = (pli + 2) % 3;
								}

								NQRS[qti][pli] = NQRS[qtj][plj];
								QRSIZES[qti ][pli ] = QRSIZES[qtj][plj];
								QRBMIS[qti ][pli ] = QRBMIS[qtj][plj];
							}
							else {
								if (NEWQR != 1)
									throw new UnknownContentTypeException("SETUP HEADER - NEWQR: " + NBMS + "(MUST be 0|1)");

								int qri = 0;
								int qi = 0;

								QRBMIS[qti][pli][qri] = input.readInt(ilog(NBMS - 1));

								if (QRBMIS[qti][pli][qri] >= NBMS)
									throw new UnknownContentTypeException("(QRBMIS[qti][pli][qri] = " + QRBMIS[qti][pli][qri] +
											") >= (NBMS = " + NBMS + ") The stream is undecodable.");

								while (true) {
									QRSIZES[qti][pli][qri] = input.readInt(ilog(62 - qi)) + 1;

									qi = qi + QRSIZES[qti][pli][qri];
									qri++;

									QRBMIS[qti][pli][qri] = input.readInt(ilog(NBMS - 1));

									if (qi < 63)
										continue;
									else if (qi > 63)
										throw new UnknownContentTypeException("qi = " + qi + "; qi > 63 - The stream is undecodable.");

									break;
								}

								NQRS[qti][pli] = qri;
							}
						}
					}

					int[][] HTS = new int[80][0];
					for (int hti = 0; hti < 80; hti++)
                        HTS[hti] = readHuffmanTable("", HTS[hti], input);

					if (logMINOR)
						Logger.minor(this, "SETUP_HEADER: left " + input.available() + " bytes (should be 0)");

					expectedPacket = Packet.INTRA_FRAME;
					break;

				case INTRA_FRAME: // first in Frame
					try {
						int firstBit = input.readBit();

						if (firstBit != 0)
							throw new DataFilterException("First bit is not zero - this is not a data packet");
					}
					catch (EOFException e) {
						throw new DataFilterException("Empty package");
					}

					int FTYPE = input.readBit();
					if (FTYPE != 0)
						throw new DataFilterException("First frame type must be Intra frame. FTYPE = " + FTYPE +
								" (0 - Intra frame; 1 - Inter frame)");

					int[] QIS = new int[3];
					int NQIS = 0;
					do {
						QIS[NQIS++] = input.readInt(6);
					} while (input.readBit() == 1 && NQIS < 3); // MOREQIS

					int reservedBits = input.readInt(3);
					if (reservedBits != 0)
						throw new DataFilterException("Reserved bits = " + reservedBits +
								" This frame is not decodable according to specification (Reserved bits should be 0)");

					// TODO: page 64 required NBITS

					expectedPacket = Packet.INTER_FRAME;
					break;

				case INTER_FRAME:
//					try {
//						int firstBit = input.readBit();
//
//						if (firstBit != 0) // TODO: some other but need it
//							break;
//					}
//					catch (EOFException e) {
//						throw new DataFilterException("Empty package");
//					}
//
//					FTYPE = input.readBit();
//					if (FTYPE == 0) { // TODO: Perhaps this may be INTRA_FRAME
//						expectedPacket = Packet.INTRA_FRAME;
//						parse(packet);
//					}
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
			throw new UnknownContentTypeException("Header type: " + typeAndMagicHeader[0] + ", expected: " + expectedType);

		for (int i=0; i < magicNumber.length; i++) {
			if (typeAndMagicHeader[i+1] != magicNumber[i])
				throw new UnknownContentTypeException("Packet header magicNumber[" + i + "]: " + typeAndMagicHeader[i+1]);
		}
	}

	// The minimum number of bits required to store a positive integer `a` in
	// two’s complement notation, or 0 for a non-positive integer a.
	private int ilog(int a) {
		if (a <= 0)
			return 0;

		int n = 0;
		while (a > 0) {
			a >>= 1;
			n++;
		}
		return n;
	}

	private int[] readHuffmanTable(String HBITS, int[] HTS, BitInputStream input) throws IOException {
		if (HBITS.length() > 32)
			throw new UnknownContentTypeException("HBITS = " + HBITS +
					"; HBITS is longer than 32 bits in length - The stream is undecodable.");

		int ISLEAF = input.readBit();
		if (ISLEAF == 1) {
			if (HTS.length == 32)
				throw new UnknownContentTypeException("HTS[hti] = " + Arrays.toString(HTS) +
						"; HTS[hti] is already 32 - The stream is undecodable.");
			int TOKEN = input.readInt(5);

			HTS = Arrays.copyOf(HTS, HTS.length + 1);
			HTS[HTS.length - 1] = TOKEN;
		} else {
			HBITS += 0;
			readHuffmanTable(HBITS, HTS, input);
			HBITS = HBITS.substring(0, HBITS.length() - 1);
			HBITS += 1;
			readHuffmanTable(HBITS, HTS, input);
		}

		return HTS;
	}

	private String longRunBitStringDecode(int NBITS, BitInputStream input) throws IOException {
		assert NBITS >= 0;

		if (NBITS == 0)
			return "";

		int[][] huffmanCodesForLongRunLengths = {
				{0, 1, 0}, // Huffman Code, RSTART, RBITS
				{Integer.parseInt("10", 2), 2, 1},
				{Integer.parseInt("110", 2), 4, 1},
				{Integer.parseInt("1110", 2), 6, 2},
				{Integer.parseInt("11110", 2), 10, 3},
				{Integer.parseInt("111110", 2), 18, 4},
				{Integer.parseInt("111111", 2), 34, 12}
		};

		int LEN = 0;
		StringBuilder BITS = new StringBuilder();

		int BIT = input.readBit();
		int RSTART = 0;
		int RBITS = 0;

		while (true) {
			int code = 0;
			for (int i = 0; i < 7; i++) {
				code = code << 1 | input.readBit();

				if (code > 63)
					throw new UnknownContentTypeException("Code: " + code +
							"; Highest Huffman Code for Long Run Lengths is b111111");

				for (int[] huffmanCode : huffmanCodesForLongRunLengths) {
					if (code == huffmanCode[0]) {
						RSTART = huffmanCode[1];
						RBITS = huffmanCode[2];
						break;
					}
				}
			}

			int ROFFS = input.readInt(RBITS);
			int RLEN = RSTART + ROFFS;

			for (int i = 0; i < RLEN; i++)
				BITS.append(BIT);

			LEN += RLEN;
			if (LEN > NBITS)
				throw new UnknownContentTypeException("LEN: " + LEN + "; NBITS: " + NBITS +
						"; LEN MUST be less than or equal to NBITS.");

			if (LEN == NBITS)
				return BITS.toString();

			if (LEN == 4129)
				BIT = input.readBit();
			else
				BIT = 1 - BIT;
		}
	}

	private String shortRunBitStringDecode(int NBITS, BitInputStream input) throws IOException {
		assert  NBITS >= 0;

		if (NBITS == 0)
			return "";

		int[][] huffmanCodesForShortRunLengths = {
				{0, 1, 1}, // Huffman Code, RSTART, RBITS
				{Integer.parseInt("10", 2), 3, 1},
				{Integer.parseInt("110", 2), 5, 1},
				{Integer.parseInt("1110", 2), 7, 2},
				{Integer.parseInt("11110", 2), 11, 2},
				{Integer.parseInt("11111", 2), 15, 4}
		};

		int LEN = 0;
		StringBuilder BITS = new StringBuilder();

		int BIT = input.readBit();
		int RSTART = 0;
		int RBITS = 0;

		while (true) {
			int code = 0;
			for (int i = 0; i < 7; i++) {
				code = code << 1 | input.readBit();

				if (code > 63)
					throw new UnknownContentTypeException("Code: " + code +
							"; Highest Huffman Code for Long Run Lengths is b111111");

				for (int[] huffmanCode : huffmanCodesForShortRunLengths) {
					if (code == huffmanCode[0]) {
						RSTART = huffmanCode[1];
						RBITS = huffmanCode[2];
						break;
					}
				}
			}

			int ROFFS = input.readInt(RBITS);
			int RLEN = RSTART + ROFFS;

			for (int i = 0; i < RLEN; i++)
				BITS.append(BIT);

			LEN += RLEN;
			if (LEN > NBITS)
				throw new UnknownContentTypeException("LEN: " + LEN + "; NBITS: " + NBITS +
						"; LEN MUST be less than or equal to NBITS.");

			if (LEN == NBITS)
				return BITS.toString();

			BIT = 1 - BIT;
		}
	}

	private int[] codedBlockFlagsDecode(int FTYPE, int NSBS, long NBS, BitInputStream input) throws IOException {
		if (FTYPE == 0) // intra frame
			return new int[NSBS];

		// inter frame
		int NBITS = NSBS;
		String BITS = longRunBitStringDecode(NBITS, input);

		int[] SBPCODED = new int[NSBS];
		for (int sbi = 0; sbi < NSBS; sbi++) {
			SBPCODED[sbi] = Character.getNumericValue(BITS.charAt(sbi));
		}

		NBITS = 0;
		for (int sbpcodedI : SBPCODED)
			if (sbpcodedI == 0)
				NBITS++;

		BITS = longRunBitStringDecode(NBITS, input);

		for (int sbi = 0; sbi < NSBS; sbi++) {
			SBPCODED[sbi] = Character.getNumericValue(BITS.charAt(sbi));
		}

		NBITS = 0;
		for (int sbpcodedI : SBPCODED)
			if (sbpcodedI == 1)
				NBITS++;

		BITS = shortRunBitStringDecode(NBITS, input);

		// TODO
		//  For each block in coded order—indexed by bi:
		//     i. Assign sbi the index of the super block containing block bi .
		//    ii. If SBPCODED[sbi] is zero, assign BCODED[bi] the value SBFCODED[sbi].
		//   iii. Otherwise, remove the bit at the head of the string BITS and assign it to BCODED[bi].

		return null; // TODO: return BCODED
	}

	private int[] macroBlockCodingModes(int FTYPE, int NMBS, long NBS, int[] BCODED, BitInputStream input) throws IOException {
		if (FTYPE == 0) { // intra frame
			int[] MBMODES = new int[NMBS];
			for (int i = 0; i < NMBS; i++)
				MBMODES[i] = 1;
			return MBMODES;
		}

		// inter frame
		int MSCHEME = input.readInt(3);
		int[] MALPHABET;
		if (MSCHEME == 0) {
			MALPHABET = new int[8];
			for (int MODE = 0; MODE <= 7; MODE++) {
				int mi = input.readInt(3);
				MALPHABET[mi] = MODE;
			}
		}
		else if (MSCHEME != 7) {
			int[][] macroBlockModeSchemes = {
					{3, 4, 2, 0, 1, 5, 6, 7},
					{3, 4, 0, 2, 1, 5, 6, 7},
					{3, 2, 4, 0, 1, 5, 6, 7},
					{3, 2, 0, 4, 1, 5, 6, 7},
					{0, 3, 4, 2, 1, 5, 6, 7},
					{0, 5, 3, 4, 2, 1, 6, 7}
			};
			MALPHABET = macroBlockModeSchemes[MSCHEME + 1];
		}

		// TODO: page 71
		//  For each consecutive macro block in coded order (cf. Section 2.4)—indexed by mbi:
		//    i. If a block bi in the luma plane of macro block mbi exists such that BCODED[bi ] is 1:
		//      A. If MSCHEME is not 7, read one bit at a time until one of
		//         the Huffman codes in Table 7.19 is recognized, and assign
		//         MBMODES[mbi ] the value MALPHABET[mi ], where mi
		//         is the index of the Huffman code decoded.
		//      B. Otherwise, read a 3-bit unsigned integer as MBMODES[mbi ].
		//    ii. Otherwise, if no luma-plane blocks in the macro block are coded,
		//        assign MBMODES[mbi ] the value 0 (INTER NOMV).

		return null; // TODO: MBMODES
	}

	private MotionVector motionVectorDecode(int MVMODE, BitInputStream input) throws IOException {
		Map<String, Integer> huffmanCodesForMotionVectorComponents = new HashMap<>(63);
		huffmanCodesForMotionVectorComponents.put("000", 0);
		huffmanCodesForMotionVectorComponents.put("001", 1);
		huffmanCodesForMotionVectorComponents.put("0110", 2);
		huffmanCodesForMotionVectorComponents.put("1000", 3);
		huffmanCodesForMotionVectorComponents.put("101000", 4);
		huffmanCodesForMotionVectorComponents.put("101010", 5);
		huffmanCodesForMotionVectorComponents.put("101100", 6);
		huffmanCodesForMotionVectorComponents.put("101110", 7);
		huffmanCodesForMotionVectorComponents.put("1100000", 8);
		huffmanCodesForMotionVectorComponents.put("1100010", 9);
		huffmanCodesForMotionVectorComponents.put("1100100", 10);
		huffmanCodesForMotionVectorComponents.put("1100110", 11);
		huffmanCodesForMotionVectorComponents.put("1101000", 12);
		huffmanCodesForMotionVectorComponents.put("1101010", 13);
		huffmanCodesForMotionVectorComponents.put("1101100", 14);
		huffmanCodesForMotionVectorComponents.put("1101110", 15);
		huffmanCodesForMotionVectorComponents.put("11100000", 16);
		huffmanCodesForMotionVectorComponents.put("11100010", 17);
		huffmanCodesForMotionVectorComponents.put("11100100", 18);
		huffmanCodesForMotionVectorComponents.put("11100110", 19);
		huffmanCodesForMotionVectorComponents.put("11101000", 20);
		huffmanCodesForMotionVectorComponents.put("11101010", 21);
		huffmanCodesForMotionVectorComponents.put("11101100", 22);
		huffmanCodesForMotionVectorComponents.put("11101110", 23);
		huffmanCodesForMotionVectorComponents.put("11110000", 24);
		huffmanCodesForMotionVectorComponents.put("11110010", 25);
		huffmanCodesForMotionVectorComponents.put("11110100", 26);
		huffmanCodesForMotionVectorComponents.put("11110110", 27);
		huffmanCodesForMotionVectorComponents.put("11111000", 28);
		huffmanCodesForMotionVectorComponents.put("11111010", 29);
		huffmanCodesForMotionVectorComponents.put("11111100", 30);
		huffmanCodesForMotionVectorComponents.put("11111110", 31);
		huffmanCodesForMotionVectorComponents.put("010", -1);
		huffmanCodesForMotionVectorComponents.put("0111", -2);
		huffmanCodesForMotionVectorComponents.put("1001", -3);
		huffmanCodesForMotionVectorComponents.put("101001", -4);
		huffmanCodesForMotionVectorComponents.put("101011", -5);
		huffmanCodesForMotionVectorComponents.put("101101", -6);
		huffmanCodesForMotionVectorComponents.put("101111", -7);
		huffmanCodesForMotionVectorComponents.put("1100001", -8);
		huffmanCodesForMotionVectorComponents.put("1100011", -9);
		huffmanCodesForMotionVectorComponents.put("1100101", -10);
		huffmanCodesForMotionVectorComponents.put("1100111", -11);
		huffmanCodesForMotionVectorComponents.put("1101001", -12);
		huffmanCodesForMotionVectorComponents.put("1101011", -13);
		huffmanCodesForMotionVectorComponents.put("1101101", -14);
		huffmanCodesForMotionVectorComponents.put("1101111", -15);
		huffmanCodesForMotionVectorComponents.put("11100001", -16);
		huffmanCodesForMotionVectorComponents.put("11100011", -17);
		huffmanCodesForMotionVectorComponents.put("11100101", -18);
		huffmanCodesForMotionVectorComponents.put("11100111", -19);
		huffmanCodesForMotionVectorComponents.put("11101001", -20);
		huffmanCodesForMotionVectorComponents.put("11101011", -21);
		huffmanCodesForMotionVectorComponents.put("11101101", -22);
		huffmanCodesForMotionVectorComponents.put("11101111", -23);
		huffmanCodesForMotionVectorComponents.put("11110001", -24);
		huffmanCodesForMotionVectorComponents.put("11110011", -25);
		huffmanCodesForMotionVectorComponents.put("11110101", -26);
		huffmanCodesForMotionVectorComponents.put("11110111", -27);
		huffmanCodesForMotionVectorComponents.put("11111001", -28);
		huffmanCodesForMotionVectorComponents.put("11111011", -29);
		huffmanCodesForMotionVectorComponents.put("11111101", -30);
		huffmanCodesForMotionVectorComponents.put("11111111", -31);

		MotionVector motionVector = new MotionVector();

		if (MVMODE == 0) {
			String code = String.valueOf(input.readBit());
			while (!huffmanCodesForMotionVectorComponents.containsKey(code)) {
				code += input.readBit();

				if (code.length() > 8)
					throw new UnknownContentTypeException("Unknown Huffman code for motion vector components: " + code);
			}
			motionVector.MVX = huffmanCodesForMotionVectorComponents.get(code);

			code = String.valueOf(input.readBit());
			while (!huffmanCodesForMotionVectorComponents.containsKey(code)) {
				code += input.readBit();

				if (code.length() > 8)
					throw new UnknownContentTypeException("Unknown Huffman code for motion vector components: " + code);
			}
			motionVector.MVY = huffmanCodesForMotionVectorComponents.get(code);
		}
		else {
			motionVector.MVX = input.readInt(5);
			if (input.readBit() == 1)
				motionVector.MVX *= -1;

			motionVector.MVY = input.readInt(5);
			if (input.readBit() == 1)
				motionVector.MVY *= -1;
		}

		return motionVector;
	}

	class MotionVector {
		private int MVX;
		private int MVY;
	}
}
