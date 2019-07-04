/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.cp.exception;

import com.hazelcast.core.HazelcastException;
import com.hazelcast.cp.CPGroup;

/**
 * Base exception for failures in the CP subsystem
 * <p>
 * This exception can include the known leader of a {@link CPGroup}
 * when it's thrown.
 * Leader endpoint can be accessed by {@link #getLeaderUuid()}, if available.
 */
public class CPSubsystemException extends HazelcastException {

    private static final long serialVersionUID = 3165333502175586105L;

    private final String leaderUuid;

    public CPSubsystemException(String leaderUuid) {
        this.leaderUuid = leaderUuid;
    }

    public CPSubsystemException(String message, String leaderUuid) {
        super(message);
        this.leaderUuid = leaderUuid;
    }

    public CPSubsystemException(String message, Throwable cause, String leaderUuid) {
        super(message, cause);
        this.leaderUuid = leaderUuid;
    }

    /**
     * Returns the leader endpoint of related CP group, if known/available
     * by the time this exception is thrown.
     */
    public String getLeaderUuid() {
        return leaderUuid;
    }
}
