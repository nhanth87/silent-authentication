/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

import java.time.Instant;
import java.util.Optional;

/**
 * Port from the CAMARA SimSwap northbound surface into the running SAS: the
 * read-only SIM-change evidence behind {@code /sim-swap/v2}. In the SAS this is
 * the same binding age the Verifier already scores as {@code notSimSwapped} —
 * MAP SAI/PSI {@code lastUpdateLocation} (2G/3G) or a read-only Sh UDR/SNR read
 * (TS 29.328/29.329, 4G/5G). It never mints or consumes authentication vectors
 * (no AIR/AIA, no IDR/IDA) and never queries an interconnect HLR (FS.11).
 *
 * <p>Fail-closed contract: {@link Optional#empty()} means "no evidence for this
 * identifier", which the resource answers as {@code 404 IDENTIFIER_NOT_FOUND} —
 * an unknown SIM-change history is never reported as "not swapped".</p>
 */
public interface SimSwapQueryPort {

    /**
     * Timestamp of the latest SIM/IMSI change known for the subscriber.
     *
     * @param msisdn normalized E.164 number ({@code +<digits>})
     * @return the last SIM change, or empty when the identifier is unknown or
     *         no binding-age evidence source is wired
     */
    Optional<Instant> lastSimChange(String msisdn);
}
