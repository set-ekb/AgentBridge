package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class ConversationRulesTest {
    @Test fun whyQuestionDoesNotPauseActiveTask() {
        assertEquals(OwnerIntent.DISCUSS,IntentRules.local("Почему по датам?",true,false))
        assertEquals(OwnerIntent.AMEND,IntentRules.local("Лучше по папкам проектов",true,false))
    }
    @Test fun quotedStopIsNotAStopCommand() {
        assertNotEquals(OwnerIntent.CANCEL,IntentRules.local("В тексте написано «стоп»",true,false))
        assertEquals(OwnerIntent.CANCEL,IntentRules.local("Стоп!",true,false))
        assertEquals(OwnerIntent.PAUSE,IntentRules.local("Пауза",true,false))
    }
    @Test fun askingWhyClarificationIsNeededDoesNotAnswerIt() {
        assertEquals(OwnerIntent.DISCUSS,IntentRules.local("Почему ты спрашиваешь?",false,true))
        assertEquals(OwnerIntent.REPLY,IntentRules.local("На телефоне 23124RA7EO",false,true))
    }
    @Test fun hypotheticalDeletionDoesNotBecomeExecution() {
        assertEquals(OwnerIntent.DISCUSS,IntentRules.local("Может, удалить дубликаты?",false,false))
        assertEquals(OwnerIntent.ROUTE,IntentRules.local("Удали эти дубликаты",false,false))
    }
    @Test fun oneQuestionCanOnlyBeAnsweredOnceAndKeepsTaskIdentity() {
        val task=TaskCheckpoint(id="same-task",goal="Изменить время",provider="gemini",model="model",status="WAIT_USER",questionId="q1",questionText="Какой телефон?",taskRevision=4)
        val answered=QuestionRules.answer(task,"q1","23124RA7EO")!!
        assertEquals(task.id,answered.id);assertEquals(5,answered.taskRevision)
        assertTrue(answered.conditions.contains("23124RA7EO"));assertTrue(answered.questionId.isEmpty())
        assertNull(QuestionRules.answer(answered,"q1","23124RA7EO"))
        assertNull(QuestionRules.answer(task,"other-question","answer"))
    }
    @Test fun persistedMessageRetainsIdentityAndGenerationForDeduplication() {
        val m=ConversationMessage(id="delivery-one",text="Лучше по папкам",taskId="task",replyTo="q",generation=3)
        assertEquals(m,MemoryRules.json.decodeFromString<ConversationMessage>(MemoryRules.json.encodeToString(m)))
    }
    @Test fun checkpointsFromVersion07StillDecodeWithoutLosingMemory() {
        val old="""{"id":"old","goal":"Task","provider":"gemini","model":"model","status":"PAUSED"}"""
        val restored=MemoryRules.json.decodeFromString<TaskCheckpoint>(old)
        assertEquals(1,restored.taskRevision);assertEquals("main",restored.conversationId)
    }
}
