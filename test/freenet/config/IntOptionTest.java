package freenet.config;

import junit.framework.TestCase;

public class IntOptionTest extends TestCase {

    public void testParseStringMillisInK() throws InvalidConfigValueException {
        IntOption intOption = new IntOption(null, "", 0, 0, false,
                false, "", "", null, Dimension.DURATION);
        assertEquals(Integer.valueOf(1200_000), intOption.parseString("1200k"));
    }

    public void testParseStringSec() throws InvalidConfigValueException {
        IntOption intOption = new IntOption(null, "", 0, 0, false,
                false, "", "", null, Dimension.DURATION);
        assertEquals(Integer.valueOf(1200_000), intOption.parseString("1200s"));
        assertEquals(Integer.valueOf(1200_000), intOption.parseString("20m"));
    }
}
