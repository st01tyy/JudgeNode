package edu.bistu.rubenoj.judgenode.config;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

@Data
public class LanguageConfig
{
    @JacksonXmlProperty(localName = "name")
    private String name;

    //absolute location
    @JacksonXmlProperty(localName = "app1")
    private String app1;

    @JacksonXmlProperty(localName = "app2")
    private String app2;
}
