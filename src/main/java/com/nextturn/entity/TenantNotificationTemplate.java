package com.nextturn.entity;

import com.nextturn.enums.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Entity mapping to {@code tenant__notification_templates} allowing Tenant Admins to customize
 * notification subject/body templates with dynamic placeholders (REQ-NOTI-04).
 */
@Entity
@Table(
        name = "tenant__notification_templates",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tenant__nt_code", columnNames = "template_code")
        },
        indexes = {
                @Index(name = "idx_tenant__nt_active", columnList = "is_active, channel")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantNotificationTemplate {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "template_code", length = 50, nullable = false)
    private String templateCode;

    @Column(name = "template_name", length = 150, nullable = false)
    private String templateName;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20, nullable = false)
    @Builder.Default
    private NotificationChannel channel = NotificationChannel.EMAIL;

    @Column(name = "subject_template", length = 255)
    private String subjectTemplate;

    @Column(name = "body_template", columnDefinition = "TEXT", nullable = false)
    private String bodyTemplate;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
