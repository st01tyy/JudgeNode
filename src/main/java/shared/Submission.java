package shared;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Submission implements Serializable
{
    private Long submissionID;
    private Long problemID;
    private Long submitTime;
    private String sourceName;
    private String sourceFileName;
    private String languageName;
    private byte[] arr;
}
