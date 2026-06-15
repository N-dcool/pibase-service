package com.pibase.pibase_api.event;

public record DatabaseProvisioningEvent(String dbId, String plainPassword) {
}
