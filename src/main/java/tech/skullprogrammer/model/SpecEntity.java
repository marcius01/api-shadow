package tech.skullprogrammer.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "specs")
@NoArgsConstructor
public class SpecEntity extends PanacheEntity {

    @Column(nullable = false, unique = true)
    public String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    @Column(columnDefinition = "TEXT")
    public String profileContent;

    public boolean active = true;

    @Column(columnDefinition = "boolean default false")
    public boolean semanticMode = false;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public SpecEntity(String name, String content) {
        this.name = name;
        this.content = content;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static Optional<SpecEntity> findByName(String name) {
        return find("name", name).firstResultOptional();
    }

    public static List<SpecEntity> findAllActive() {
        return list("active", true);
    }
}
