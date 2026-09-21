package eu.darken.porter.core

/**
 * What a wire admits on the caller's identity alone, beyond the policy and the caller's record.
 * Each endpoint hands its own to every gated operation, so the core never learns which wire a
 * call arrived on.
 */
interface CallerExemption {

    /** An attached caller this wire answers without a grant. */
    fun waivesGrant(caller: CallerIdentity): Boolean

    /** A caller this wire answers as permitted without a record. */
    fun answersWithoutRecord(caller: CallerIdentity): Boolean

    /** Attached and allowed, whoever the caller is. */
    object None : CallerExemption {

        override fun waivesGrant(caller: CallerIdentity): Boolean = false

        override fun answersWithoutRecord(caller: CallerIdentity): Boolean = false
    }
}
