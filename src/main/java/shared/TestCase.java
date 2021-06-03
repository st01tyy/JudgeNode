package shared;

import java.io.Serializable;

public class TestCase implements Serializable
{
    private static final long serialVersionUID = 19990914L;

    private byte[] input;
    private byte[] output;

    public TestCase() {
    }

    public TestCase(byte[] input, byte[] output) {
        this.input = input;
        this.output = output;
    }

    public byte[] getInput() {
        return input;
    }

    public void setInput(byte[] input) {
        this.input = input;
    }

    public byte[] getOutput() {
        return output;
    }

    public void setOutput(byte[] output) {
        this.output = output;
    }
}
