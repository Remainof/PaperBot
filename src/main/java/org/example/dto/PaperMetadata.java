package org.example.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class PaperMetadata {
    private String title;
    private String authors;
    private String paperAbstract;
    private Integer year;
    private String keywords;
    private String venue;
}
