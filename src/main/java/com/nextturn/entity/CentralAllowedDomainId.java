package com.nextturn.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Composite primary key for {@link CentralAllowedDomain} mapping to {@code central__allowed_domains}
 * (*khóa chính kết hợp cho danh sách tên miền được phép của cơ sở*).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CentralAllowedDomainId implements Serializable {

    private String id;
    private String tenantId;
}
