import network.darkland.protocol.NexusJsonDataContainer;
import network.darkland.redis.security.*;

/** Run once without a key, then with explicit NEXUS_ALLOW_UNSIGNED_MESSAGES=true. */
public class SignaturePolicyAudit {
    public static void main(String[] args) {
        if(NexusSecurityConfig.isSigningEnabled()) throw new AssertionError("Test process must have no signing key");
        boolean accepted=new SignatureValidator().validate(new NexusJsonDataContainer()).valid();
        if(accepted!=NexusSecurityConfig.ALLOW_UNSIGNED_MESSAGES) throw new AssertionError("Unexpected unsigned policy");
        System.out.println("SECURITY PASS no-key unsigned accepted="+accepted+" explicit opt-in="+NexusSecurityConfig.ALLOW_UNSIGNED_MESSAGES);
    }
}
