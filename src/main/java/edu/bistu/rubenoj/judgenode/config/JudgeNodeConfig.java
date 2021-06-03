package edu.bistu.rubenoj.judgenode.config;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import lombok.Data;

import java.util.List;

@Data
@JacksonXmlRootElement(namespace = "edu.bistu.rubenoj.judgenode", localName = "judgenode")
public class JudgeNodeConfig
{
    @JacksonXmlProperty(localName = "webserver")
    private String webServer;

    @JacksonXmlProperty(localName = "kafka")
    private String kafkaServer;

    @JacksonXmlProperty(localName = "thread")
    private Integer workThread;

    @JacksonXmlProperty(localName = "workspace")
    private String workspace;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "language")
    private List<LanguageConfig> languageConfigList;
}
