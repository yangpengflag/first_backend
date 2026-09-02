package com.mooc.backend.places.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitySlugsTest {

    @Test
    @DisplayName("纯英文单词直接小写")
    void lowercasesPlainEnglishWord() {
        assertThat(CitySlugs.slugify("Hangzhou")).isEqualTo("hangzhou");
        assertThat(CitySlugs.slugify("Shanghai")).isEqualTo("shanghai");
    }

    @Test
    @DisplayName("撇号与空格折叠为单连字符")
    void collapsesApostropheAndSpaces() {
        assertThat(CitySlugs.slugify("Xi'an")).isEqualTo("xi-an");
        assertThat(CitySlugs.slugify("New York")).isEqualTo("new-york");
    }

    @Test
    @DisplayName("去除首尾连字符并压缩重复分隔符")
    void trimsEdgesAndCollapsesRepeatedSeparators() {
        assertThat(CitySlugs.slugify("  Zhangjiajie  ")).isEqualTo("zhangjiajie");
        assertThat(CitySlugs.slugify("Bao  Tou")).isEqualTo("bao-tou");
    }
}
