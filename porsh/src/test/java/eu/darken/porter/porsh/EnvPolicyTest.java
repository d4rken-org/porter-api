package eu.darken.porter.porsh;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class EnvPolicyTest {

    private static final String TERMUX_PATH = "PATH=/data/data/com.termux/files/usr/bin";

    @Test
    public void underRoot_theEnvironmentIsKept() {
        String[] env = {TERMUX_PATH};

        assertSame(env, new EnvPolicy(true).resolve(env));
    }

    @Test
    public void underAdb_theEnvironmentIsDropped() {
        String[] env = {TERMUX_PATH};

        assertNull(new EnvPolicy(false).resolve(env));
    }

    @Test
    public void porshPreserveEnvZero_overridesTheRootDefault() {
        String[] env = {TERMUX_PATH, "PORSH_PRESERVE_ENV=0"};

        assertNull(new EnvPolicy(true).resolve(env));
    }

    @Test
    public void porshPreserveEnvOne_overridesTheAdbDefault() {
        String[] env = {TERMUX_PATH, "PORSH_PRESERVE_ENV=1"};

        assertSame(env, new EnvPolicy(false).resolve(env));
    }

    @Test
    public void rishPreserveEnvOne_isStillHonoured() {
        String[] env = {TERMUX_PATH, "RISH_PRESERVE_ENV=1"};

        assertSame(env, new EnvPolicy(false).resolve(env));
    }

    @Test
    public void rishPreserveEnvZero_isStillHonoured() {
        String[] env = {TERMUX_PATH, "RISH_PRESERVE_ENV=0"};

        assertNull(new EnvPolicy(true).resolve(env));
    }

    @Test
    public void porshPreserveEnv_winsOverRishPreserveEnv() {
        String[] rishFirst = {"RISH_PRESERVE_ENV=0", "PORSH_PRESERVE_ENV=1"};
        assertSame(rishFirst, new EnvPolicy(false).resolve(rishFirst));

        String[] porshFirst = {"PORSH_PRESERVE_ENV=0", "RISH_PRESERVE_ENV=1"};
        assertNull(new EnvPolicy(true).resolve(porshFirst));
    }
}
