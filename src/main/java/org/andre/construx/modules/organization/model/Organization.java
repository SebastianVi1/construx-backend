package org.andre.construx.modules.organization.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@NoArgsConstructor @AllArgsConstructor
@Builder @Table(name = "organization")
@Getter
public class Organization {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    private String status;

}
