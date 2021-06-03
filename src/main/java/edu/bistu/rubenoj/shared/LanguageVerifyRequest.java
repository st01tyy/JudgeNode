package edu.bistu.rubenoj.shared;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class LanguageVerifyRequest
{
    private List<String> languageNameList;
}
