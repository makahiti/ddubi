package com.ssafy.ddubi.domain.user.dto.response;

import com.ssafy.ddubi.domain.user.entity.TransportMode;
import com.ssafy.ddubi.domain.user.entity.TransportPreference;
import com.ssafy.ddubi.domain.user.entity.UserTransportPref;

public record TransportPrefResponse(
        TransportMode mode,
        TransportPreference preference
) {

    public static TransportPrefResponse of(UserTransportPref pref) {
        return new TransportPrefResponse(pref.getMode(), pref.getPreference());
    }
}
