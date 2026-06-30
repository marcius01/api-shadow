package tech.skullprogrammer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import tech.skullprogrammer.model.MockProfile;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class MockProfileLoader {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final Map<String, MockProfile> profiles = new HashMap<>();
    private final Map<String, MockProfile> profilesByName = new ConcurrentHashMap<>();

    public MockProfile load(String path) {
        try {
            MockProfile profile = yaml.readValue(new File(path), MockProfile.class);
            profiles.put(path, profile);
            return profile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load mock profile: " + path, e);
        }
    }

    public MockProfile reload(String path) {
        profiles.remove(path);
        return load(path);
    }

    public MockProfile get(String path) {
        return profiles.get(path);
    }

    public MockProfile loadFromContent(String yamlContent) {
        try {
            return yaml.readValue(yamlContent, MockProfile.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse mock profile YAML", e);
        }
    }

    public String toYaml(MockProfile profile) {
        try {
            return yaml.writeValueAsString(profile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize mock profile to YAML", e);
        }
    }

    public void registerByName(String specName, MockProfile profile) {
        profilesByName.put(specName, profile);
    }

    public void removeByName(String specName) {
        profilesByName.remove(specName);
    }

    public MockProfile getByName(String specName) {
        return profilesByName.get(specName);
    }
}
