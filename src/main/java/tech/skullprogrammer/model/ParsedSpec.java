package tech.skullprogrammer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedSpec {

    private String id;
    private String title;
    private String version;
    private List<MockEndpoint> endpoints;
}
