package shared;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Checker implements Serializable
{
    private String fileName;
    private String languageName;
    private byte[] arr;
}
