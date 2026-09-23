package com.ecobank.rccportal.dto;

public record UserRequest(

        String username,

        String fullName,

        String email,

        Integer roleId,

        Integer serviceId,

        Boolean active

) {
}