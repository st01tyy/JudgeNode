package edu.bistu.rubenoj.judgenode;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Deserializer;
import shared.Submission;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

@Slf4j
public class MyDeserializer implements Deserializer<Submission>
{

    @Override
    public Submission deserialize(String s, byte[] bytes)
    {
        log.info(String.valueOf(bytes.length));
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        ObjectInputStream objectInputStream = null;
        try {
            objectInputStream = new ObjectInputStream(byteArrayInputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            assert objectInputStream != null;
            return (Submission) objectInputStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }
}
