package eu.darken.porter.core

import rikka.shizuku.server.ClientRecord

/** The record a caller is attached to, and whether this attach is what created it. */
class AttachResult(val record: ClientRecord, val created: Boolean)
