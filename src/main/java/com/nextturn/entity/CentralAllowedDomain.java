package com.nextturn.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity mapping to {@code central__allowed_domains} table in the master database schema ({@code nextturn_master}).
 * Represents whitelisted web domains permitted to embed the booking/queue widget for a specific tenant facility.
 * (*Thực thể lưu trữ danh sách tên miền được phép tích hợp tiện ích nhúng widget của từng cơ sở*).
 */
@Entity
@Table(
        name = "central__allowed_domains",
        catalog = "nextturn_master",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_central__allowed_domains_tenant_domain", columnNames = {"tenant_id", "domain_name"})
        },
        indexes = {
                @Index(name = "idx_central__allowed_domains_name", columnList = "domain_name")
        }
)
@IdClass(CentralAllowedDomainId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CentralAllowedDomain {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Id
    @Column(name = "tenant_id", length = 36, nullable = false)
    private String tenantId;

    @Column(name = "domain_name", length = 255, nullable = false)
    private String domainName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
