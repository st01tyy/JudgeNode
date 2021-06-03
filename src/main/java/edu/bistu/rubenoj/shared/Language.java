package edu.bistu.rubenoj.shared;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class Language
{
    private String languageName;
    private String topicName;
    private String compileCommand;
    private String executeCommand;
}
