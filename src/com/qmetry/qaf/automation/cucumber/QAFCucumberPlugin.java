/**
 * 
 */
package com.qmetry.qaf.automation.cucumber;

import static com.qmetry.qaf.automation.core.ConfigurationManager.getBundle;
import static com.qmetry.qaf.automation.data.MetaDataScanner.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.impl.LogFactoryImpl;

import com.qmetry.qaf.automation.core.CheckpointResultBean;
import com.qmetry.qaf.automation.core.LoggingBean;
import com.qmetry.qaf.automation.core.MessageTypes;
import com.qmetry.qaf.automation.core.QAFTestBase;
import com.qmetry.qaf.automation.core.TestBaseProvider;
import com.qmetry.qaf.automation.integration.ResultUpdator;
import com.qmetry.qaf.automation.integration.TestCaseResultUpdator;
import com.qmetry.qaf.automation.integration.TestCaseRunResult;
import com.qmetry.qaf.automation.keys.ApplicationProperties;
import com.qmetry.qaf.automation.util.ClassUtil;
import com.qmetry.qaf.automation.util.Reporter;
import com.qmetry.qaf.automation.util.StringMatcher;
import com.qmetry.qaf.automation.util.StringUtil;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EmbedEvent;
import io.cucumber.plugin.event.EventHandler;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import io.cucumber.plugin.event.WriteEvent;

/**
 * @author chirag.jayswal
 *
 */
public class QAFCucumberPlugin implements ConcurrentEventListener {
	private static final Log logger = LogFactoryImpl.getLog(QAFCucumberPlugin.class);

	/*
	 * (non-Javadoc)
	 * 
	 * @s() { }ee
	 * io.cucumber.plugin.ConcurrentEventListener#setEventPublisher(io.cucumber.
	 * plugin.event.EventPublisher)
	 */
	@Override
	public void setEventPublisher(EventPublisher publisher) {
		setCucumberRunner(true);

		publisher.registerHandlerFor(TestRunStarted.class, runStartedHandler);
		publisher.registerHandlerFor(TestRunFinished.class, runFinishedHandler);

		publisher.registerHandlerFor(TestCaseStarted.class, tcStartedHandler);
		publisher.registerHandlerFor(TestCaseFinished.class, tcfinishedHandler);

		publisher.registerHandlerFor(TestStepStarted.class, stepStartedHandler);
		publisher.registerHandlerFor(TestStepFinished.class, stepfinishedHandler);
		publisher.registerHandlerFor(EmbedEvent.class, embedEventHandler);
		publisher.registerHandlerFor(WriteEvent.class, (event) -> {
			Reporter.log(event.getText());
		});

	}

	private EventHandler<EmbedEvent> embedEventHandler = new EventHandler<EmbedEvent>() {

		@Override
		public void receive(EmbedEvent event) {
			// event.
		}
	};

	private EventHandler<TestStepStarted> stepStartedHandler = new EventHandler<TestStepStarted>() {

		@Override
		public void receive(TestStepStarted event) {
			// TestStep step = event.getTestStep();
			QAFTestBase stb = TestBaseProvider.instance().get();
			ArrayList<CheckpointResultBean> allResults = new ArrayList<CheckpointResultBean>(
					stb.getCheckPointResults());
			ArrayList<LoggingBean> allCommands = new ArrayList<LoggingBean>(stb.getLog());
			stb.getCheckPointResults().clear();
			stb.getLog().clear();
			stb.getContext().setProperty("allResults", allResults);
			stb.getContext().setProperty("allCommands", allCommands);

		}
	};
	private EventHandler<TestStepFinished> stepfinishedHandler = new EventHandler<TestStepFinished>() {

		@Override
		public void receive(TestStepFinished event) {
			if (event.getTestStep() instanceof PickleStepTestStep) {
				logStep((PickleStepTestStep) event.getTestStep(), event.getResult());
			}
		}

		@SuppressWarnings("unchecked")
		private void logStep(PickleStepTestStep testStep, Result result) {
			String stepText = testStep.getStep().getKeyWord() + testStep.getStepText();
			Long duration = result.getDuration().toMillis();
			QAFTestBase stb = TestBaseProvider.instance().get();

			if (result.getError() != null) {
				CheckpointResultBean failureCheckpoint = new CheckpointResultBean();
				failureCheckpoint.setMessage(result.getError().getMessage());
				failureCheckpoint.setType(MessageTypes.Fail);
				stb.getCheckPointResults().add(failureCheckpoint);
			}

			MessageTypes type = result.getStatus().is(Status.PASSED)
					&& getStepMessageType(stb.getCheckPointResults()).isFailure() ? MessageTypes.TestStepFail
							: getStepMessageType(result.getStatus());
			// MessageTypes type = success? getStepMessageType(stb.getCheckPointResults()) :
			// MessageTypes.;

			LoggingBean stepLogBean = new LoggingBean(testStep.getPattern(),
					testStep.getDefinitionArgument().stream().map(a -> {
						return a.getValue();
					}).collect(Collectors.toList()).toArray(new String[] {}),
					result.getStatus().name());
			stepLogBean.setSubLogs(new ArrayList<LoggingBean>(stb.getLog()));

			CheckpointResultBean stepResultBean = new CheckpointResultBean();
			stepResultBean.setMessage(stepText);
			stepResultBean.setSubCheckPoints(new ArrayList<CheckpointResultBean>(stb.getCheckPointResults()));
			stepResultBean.setDuration(duration.intValue());

			stepResultBean.setType(type);

			ArrayList<CheckpointResultBean> allResults = (ArrayList<CheckpointResultBean>) stb.getContext()
					.getObject("allResults");
			ArrayList<LoggingBean> allCommands = (ArrayList<LoggingBean>) stb.getContext().getObject("allCommands");
			stb.getContext().clearProperty("allResults");
			stb.getContext().clearProperty("allCommands");

			allResults.add(stepResultBean);

			stb.getCheckPointResults().clear();
			stb.getCheckPointResults().addAll(allResults);

			allCommands.add(stepLogBean);
			stb.getLog().clear();
			stb.getLog().addAll(allCommands);
		}

		private MessageTypes getStepMessageType(List<CheckpointResultBean> subSteps) {
			MessageTypes type = MessageTypes.TestStepPass;
			for (CheckpointResultBean subStep : subSteps) {
				type = MessageTypes.TestStepPass;
				if (StringMatcher.containsIgnoringCase("fail").match(subStep.getType())) {
					return MessageTypes.TestStepFail;
				}
				if (StringMatcher.containsIgnoringCase("warn").match(subStep.getType())) {
					type = MessageTypes.Warn;
				}
			}
			return type;
		}

		private MessageTypes getStepMessageType(io.cucumber.plugin.event.Status status) {
			switch (status) {
			case PASSED:
				return MessageTypes.TestStepPass;
			case FAILED:
			case UNDEFINED:
				return MessageTypes.TestStepFail;
			case AMBIGUOUS:
				return MessageTypes.Warn;
			default:
				return MessageTypes.TestStep;
			}
		}
	};

	private EventHandler<TestCaseStarted> tcStartedHandler = new EventHandler<TestCaseStarted>() {

		@Override
		public void receive(TestCaseStarted event) {
			Bdd2Pickle bdd2Pickle = getBdd2Pickle(event.getTestCase());
			bdd2Pickle.getMetaData().put("Referece", event.getTestCase().getUri());
			QAFTestBase stb = TestBaseProvider.instance().get();
			stb.getLog().clear();
			stb.clearVerificationErrors();
			stb.getCheckPointResults().clear();
		}
	};

	private EventHandler<TestCaseFinished> tcfinishedHandler = new EventHandler<TestCaseFinished>() {

		@Override
		public void receive(TestCaseFinished event) {
			try {
				TestCase tc = event.getTestCase();
				Bdd2Pickle bdd2Pickle = getBdd2Pickle(tc);
				boolean isDryRun = (boolean) getField("dryRun", tc);
				if (isDryRun) {
					Map<String, Object> metadata = new HashMap<String, Object>(bdd2Pickle.getMetaData());
					metadata.putAll(bdd2Pickle.getTestData());
					String result = applyMetaRule(metadata );
					if (StringUtil.isNotBlank(result)) {
						Reporter.log(result, MessageTypes.Fail);
					}
				}
				QAFTestBase stb = TestBaseProvider.instance().get();
				final List<CheckpointResultBean> checkpoints = new ArrayList<CheckpointResultBean>(
						stb.getCheckPointResults());
				final List<LoggingBean> logs = new ArrayList<LoggingBean>(stb.getLog());
				Result result = event.getResult();
				if (stb.getVerificationErrors() > 0 && result.getStatus().is(Status.PASSED)) {
					result = new Result(Status.FAILED, result.getDuration(), result.getError());
					try {
						ClassUtil.setField("result", event, result);
					} catch (Exception e) {
					}
				}
				QAFReporter.createMethodResult(tc, bdd2Pickle, result, logs, checkpoints);
				if (!isDryRun) {
					deployResult(bdd2Pickle, tc, result);
				}
				String useSingleSeleniumInstance = getBundle().getString("selenium.singletone", "");
				if (useSingleSeleniumInstance.toUpperCase().startsWith("M")) {
					stb.tearDown();
				}
			} catch (Exception e) {
				logger.error("QAFCucumberPlugin unable to process TestCaseFinished event", e);
			}
		}

		private void deployResult(Bdd2Pickle bdd2Pickle, TestCase tc, Result eventresult) {
			String updator = getBundle().getString("result.updator");
			try {
				if (StringUtil.isNotBlank(updator)) {
					TestCaseRunResult result = eventresult.getStatus() == Status.PASSED ? TestCaseRunResult.PASS
							: eventresult.getStatus() == Status.FAILED ? TestCaseRunResult.FAIL
									: TestCaseRunResult.SKIPPED;

					// String method = testCase.getName();
					Class<?> updatorCls = Class.forName(updator);

					TestCaseResultUpdator updatorObj = (TestCaseResultUpdator) updatorCls.newInstance();
					Map<String, Object> params = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);

					params.put("name", tc.getName());
					params.put("duration", eventresult.getDuration().toMillis());

					// Bdd2Pickle bdd2Pickle = getBdd2Pickle(event.getTestCase());
					if (null != bdd2Pickle) {
						params.putAll(bdd2Pickle.getMetaData());
						Map<String, Object> testData = bdd2Pickle.getTestData();
						if (testData != null) {
							params.put("testdata", testData);
							String identifierKey = ApplicationProperties.TESTCASE_IDENTIFIER_KEY
									.getStringVal("testCaseId");
							if (testData.containsKey(identifierKey)) {
								params.put(identifierKey, testData.get(identifierKey));
							}
						}
					}

					QAFTestBase testBase = TestBaseProvider.instance().get();
					ResultUpdator.updateResult(result, testBase.getHTMLFormattedLog() + testBase.getAssertionsLog(),
							updatorObj, params);
				}
			} catch (Exception e) {
				logger.warn("QAFCucumberPlugin unable to deploy result", e);
			}
		}
	};

	private EventHandler<TestRunStarted> runStartedHandler = new EventHandler<TestRunStarted>() {
		@Override
		public void receive(TestRunStarted event) {
			startReport(event);
		}

		private void startReport(TestRunStarted event) {
			QAFReporter.createMetaInfo();
		}
	};
	private EventHandler<TestRunFinished> runFinishedHandler = new EventHandler<TestRunFinished>() {
		@Override
		public void receive(TestRunFinished event) {
			endReport(event);
		}

		private void endReport(TestRunFinished event) {
			QAFReporter.updateMetaInfo();
			QAFReporter.updateOverview(null, true);
			;

			TestBaseProvider.instance().stopAll();
			ResultUpdator.awaitTermination();
		}
	};

	private static Bdd2Pickle getBdd2Pickle(Object testCase) {
		try {
			Object pickle = getField("pickle", testCase);
			if (pickle instanceof Bdd2Pickle) {
				return ((Bdd2Pickle) pickle);
			} else {
				return getBdd2Pickle(pickle);
			}
		} catch (Exception e) {
			return null;
		}
	}

	public static Object getField(String fieldName, Object classObj) {
		try {

			Field field = null;
			try {
				field = classObj.getClass().getField(fieldName);
			} catch (NoSuchFieldException e) {
				Field[] fields = ClassUtil.getAllFields(classObj.getClass(), Object.class);
				for (Field f : fields) {
					if (f.getName().equalsIgnoreCase(fieldName)) {
						field = f;
						break;
					}
				}
			}

			field.setAccessible(true);
			Field modifiersField = Field.class.getDeclaredField("modifiers");
			modifiersField.setAccessible(true);
			modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
			return field.get(classObj);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private void setCucumberRunner(boolean cucumberRunner) {
		getBundle().setProperty("cucumber.run.mode", cucumberRunner);
	}

}
