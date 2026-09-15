package eu.darken.porter.core;

import rikka.shizuku.server.ClientRecord;

/** The record a caller is attached to, and whether this attach is what created it. */
public final class AttachResult {

    public final ClientRecord record;
    public final boolean created;

    public AttachResult(ClientRecord record, boolean created) {
        this.record = record;
        this.created = created;
    }
}
