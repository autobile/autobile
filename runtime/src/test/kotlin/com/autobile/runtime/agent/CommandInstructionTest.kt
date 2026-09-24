package com.autobile.runtime.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A typed command that names a saved automation either just runs it, or runs it with
 * something extra for this time. Getting that wrong one way loses the user's request; the
 * other way hands every ordinary run to the agent.
 */
class CommandInstructionTest {

    @Test
    fun `asking to run an automation carries no instruction`() {
        assertThat(instructionBeyondName("Run Daily Sales now", "Daily Sales")).isEmpty()
        assertThat(instructionBeyondName("데일리 매출 실행해줘", "데일리 매출")).isEmpty()
    }

    @Test
    fun `a particle attached to the name is not an instruction`() {
        assertThat(instructionBeyondName("데일리매출을 실행해줘", "데일리매출")).isEmpty()
    }

    @Test
    fun `anything more than asking for a run is kept whole`() {
        assertThat(instructionBeyondName("스도쿠 게임 어려움으로 해줘", "스도쿠 게임"))
            .isEqualTo("스도쿠 게임 어려움으로 해줘")
        assertThat(instructionBeyondName("Daily Sales but post to #ops instead", "Daily Sales"))
            .isEqualTo("Daily Sales but post to #ops instead")
    }
}
