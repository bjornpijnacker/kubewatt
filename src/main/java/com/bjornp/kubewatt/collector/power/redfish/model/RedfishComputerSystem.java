package com.bjornp.kubewatt.collector.power.redfish.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class RedfishComputerSystem {
    @SerializedName("Members")
    private List<Member> members;

    @Getter
    @AllArgsConstructor
    public static class Member {
        @SerializedName("@odata.id")
        private String id;
    }
}
