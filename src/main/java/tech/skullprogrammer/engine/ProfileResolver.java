package tech.skullprogrammer.engine;

import jakarta.enterprise.context.ApplicationScoped;
import tech.skullprogrammer.model.MockProfile;

import java.util.Map;

@ApplicationScoped
public class ProfileResolver {

    // T019: resolve EndpointOverride for path+method from profile
    public MockProfile.EndpointOverride resolve(String endpointPath, String method, MockProfile profile) {
        if (profile == null || profile.getOverrides() == null) return null;
        Map<String, MockProfile.EndpointOverride> methodMap = profile.getOverrides().get(endpointPath);
        if (methodMap == null) return null;
        return methodMap.get(method.toUpperCase());
    }

    // T020: resolve static fixture for path+method from profile
    public Object resolveFixture(String endpointPath, String method, MockProfile profile) {
        if (profile == null || profile.getFixtures() == null) return null;
        Map<String, MockProfile.FixtureResponse> methodMap = profile.getFixtures().get(endpointPath);
        if (methodMap == null) return null;
        MockProfile.FixtureResponse fixture = methodMap.get(method.toUpperCase());
        if (fixture == null) return null;
        return fixture.getStaticData();
    }
}
