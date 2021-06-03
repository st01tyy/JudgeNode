package edu.bistu.rubenoj.judgenode;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RunResult
{
    private int result;
    private Double cpuTime;
    private Long memory;
}
