package eu.darken.porter.porsh;

/**
 * Decides whether the client's environment is handed to the forked shell.
 *
 * <p>Termux points PATH and LD_PRELOAD at its own internal directories, which a shell
 * running as adb is not permitted to read. So under adb the client has to opt in, and
 * under root the environment is kept unless the client opts out.
 *
 * <p>Both spellings are accepted because this server also serves Shizuku's own rish
 * client through the compatibility companion, and that one sets RISH_PRESERVE_ENV.
 */
class EnvPolicy {

    private static final String PORSH_ON = "PORSH_PRESERVE_ENV=1";
    private static final String PORSH_OFF = "PORSH_PRESERVE_ENV=0";
    private static final String RISH_ON = "RISH_PRESERVE_ENV=1";
    private static final String RISH_OFF = "RISH_PRESERVE_ENV=0";

    private final boolean rootDefault;

    EnvPolicy(boolean rootDefault) {
        this.rootDefault = rootDefault;
    }

    /**
     * The environment to pass on, or null when it has to be dropped.
     */
    String[] resolve(String[] env) {
        return allows(env) ? env : null;
    }

    private boolean allows(String[] env) {
        Boolean porsh = null;
        Boolean rish = null;

        for (String e : env) {
            if (porsh == null && PORSH_ON.equals(e)) {
                porsh = Boolean.TRUE;
            } else if (porsh == null && PORSH_OFF.equals(e)) {
                porsh = Boolean.FALSE;
            } else if (rish == null && RISH_ON.equals(e)) {
                rish = Boolean.TRUE;
            } else if (rish == null && RISH_OFF.equals(e)) {
                rish = Boolean.FALSE;
            }
        }

        if (porsh != null) {
            return porsh;
        }
        if (rish != null) {
            return rish;
        }
        return rootDefault;
    }
}
