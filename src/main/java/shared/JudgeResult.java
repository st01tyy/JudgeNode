package shared;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
public class JudgeResult
{
    private Long submissionID;
    private SubmissionResult result;
    private Integer executionTime;
    private Integer memoryUsage;

    public JudgeResult()
    {
        this(0, null, null);
    }

    public JudgeResult(int n, Double timeSum, Long maxMemory)
    {
        executionTime = 0;
        memoryUsage = 0;
        if(n > 0 && timeSum != null)
        {
            timeSum *= 1000;
            timeSum /= n;
            executionTime = timeSum.intValue();
        }
        if(maxMemory != null && maxMemory > 0)
        {
            maxMemory /= 1024;
            memoryUsage = maxMemory.intValue();
        }
    }
}
