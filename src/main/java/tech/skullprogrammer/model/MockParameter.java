package tech.skullprogrammer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MockParameter {

    private String name;
    private String in; // "query", "path", "header"
    private String type;
    private String format;
    private boolean required;
    private String description;
}
