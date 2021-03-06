/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * The Federal Office of Administration (Bundesverwaltungsamt, BVA)
 * licenses this file to you under the Apache License, Version 2.0 (the
 * License). You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package de.bund.bva.pliscommon.serviceapi.core.serviceimpl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Required;

import de.bund.bva.isyfact.logging.IsyLogger;
import de.bund.bva.isyfact.logging.IsyLoggerFactory;
import de.bund.bva.isyfact.logging.LogKategorie;
import de.bund.bva.pliscommon.exception.PlisException;
import de.bund.bva.pliscommon.exception.PlisTechnicalRuntimeException;
import de.bund.bva.pliscommon.exception.service.PlisTechnicalToException;
import de.bund.bva.pliscommon.exception.service.PlisToException;
import de.bund.bva.pliscommon.serviceapi.common.exception.PlisExceptionMapper;
import de.bund.bva.pliscommon.serviceapi.common.konstanten.EreignisSchluessel;

/**
 * Eine generische Exception-Fassade für Service- und Native-GUI-Komponenten. Diese Implementierung fängt alle
 * Exceptions und behandelt sie folgendermaßen:
 *
 * <ul>
 * <li>Eine {@link PlisException} wird auf einem konfigurierbaren Loglevel geloggt (siehe
 * {@link #setLogLevelPlisExceptions(Level)}). Sie wird auf eine {@link PlisToException} abgebildet, die pro
 * konkreter {@link PlisException}-Subklasse konfigurierbar ist (siehe
 * {@link #setExceptionMappingSource(ExceptionMappingSource)}).</li>
 * <li>Eine {@link PlisTechnicalRuntimeException} wird auf Level ERROR geloggt und auf eine global
 * konfigurierbare {@link PlisTechnicalToException} abgebildet (siehe
 * {@link #setExceptionMappingSource(ExceptionMappingSource)}).</li>
 * <li>Eine sonstige Exception zunächst in eine global konfigurierbare {@link PlisTechnicalRuntimeException}
 * gewrappt ({@link #setAppTechnicalRuntimeException(Class)}) und dann auf Level ERROR geloggt. Durch das
 * Wrapping werden eine Ausnahme-ID und Unique-ID erzeugt, die dann sowohl im Server- als auch im Aufrufer-Log
 * erscheinen. Die gewrappte Exception wird dann auf die global konfigurierbare
 * {@link PlisTechnicalToException} abgebildet.
 * </ul>
 *
 */
public class ServiceExceptionFassade implements MethodInterceptor, Validatable {

    /** Isy-Logger. */
    private static final IsyLogger LOG = IsyLoggerFactory.getLogger(ServiceExceptionFassade.class);

    /** Methoden-Mapping. */
    private MethodMappingSource methodMappingSource;

    /** Quelle für das Exception-Mapping. */
    private ExceptionMappingSource exceptionMappingSource;

    /** Der Ausnahme-ID-Ermittler. */
    private AusnahmeIdErmittler ausnahmeIdErmittler;

    /** Konstruktor der generischen, technischen PlisRuntimeException der Anwendung. */
    private Constructor<? extends PlisTechnicalRuntimeException> appTechnicalRuntimeExceptionCon;

    /**
     * Das Log-Level, auf dem (checked) {@link PlisException PlisExceptions} geloggt werden. Checked
     * Exceptions sind vorhergesehene fachliche oder technische Fehler, die keinen unerwarteten
     * Betriebszustand darstellen. Sie sollen deshalb unter Umständen nicht ins ERROR-Log geschrieben werden,
     * um den Betrieb nicht grundlos zu alarmieren. Hier kann ein feinerer Log-Level konfiguriert werden oder
     * <code>null</code>, um checked Exceptions gar nicht zu loggen.
     */
    private String logLevelPlisExceptions = "ERROR";

    @Required
    public void setMethodMappingSource(MethodMappingSource methodMappingSource) {
        this.methodMappingSource = methodMappingSource;
    }

    @Required
    public void setExceptionMappingSource(ExceptionMappingSource exceptionMappingSource) {
        this.exceptionMappingSource = exceptionMappingSource;
    }

    @Required
    public void setAusnahmeIdErmittler(AusnahmeIdErmittler ausnahmeIdErmittler) {
        this.ausnahmeIdErmittler = ausnahmeIdErmittler;
    }

    @Required
    public void setAppTechnicalRuntimeException(
        Class<? extends PlisTechnicalRuntimeException> appTechnicalRuntimeException) {
        try {
            this.appTechnicalRuntimeExceptionCon =
                appTechnicalRuntimeException.getConstructor(String.class, Throwable.class, String[].class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Die Klasse " + appTechnicalRuntimeException.getName()
                + " hat nicht den benötigten Konstruktor "
                + "(String ausnahmeId, Throwable cause, String... parameter)");
        }
    }

    public void setLogLevelPlisExceptions(String logLevelPlisExceptions) {
        this.logLevelPlisExceptions = logLevelPlisExceptions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {

        Method remoteBeanMethod = invocation.getMethod();

        try {
            return invocation.proceed();
        } catch (PlisException e) {
            if (this.logLevelPlisExceptions != null) {
                // Workaround, da Isy-Logging keine Übergabe des Loglevels unterstützt.
                if ("INFO".equals(this.logLevelPlisExceptions)) {
                    LOG.info(LogKategorie.JOURNAL, "Fehler in der Serviceoperation {}", e,
                        getMethodSignatureString(invocation));
                } else if ("DEBUG".equals(this.logLevelPlisExceptions)) {
                    LOG.debug("Fehler in der Serviceoperation {}: {}", getMethodSignatureString(invocation),
                        e.getMessage());
                } else if ("WARN".equals(this.logLevelPlisExceptions)) {
                    LOG.warn("Fehler in der Serviceoperation {}", e, getMethodSignatureString(invocation));
                } else if ("ERROR".equals(this.logLevelPlisExceptions)) {
                    LOG.error("Fehler in der Serviceoperation {}", e, getMethodSignatureString(invocation));
                } else if ("FATAL".equals(this.logLevelPlisExceptions)) {
                    LOG.fatal("Fehler in der Serviceoperation {}", e, getMethodSignatureString(invocation));
                } else if ("TRACE".equals(this.logLevelPlisExceptions)) {
                    LOG.trace("Fehler in der Serviceoperation {}: {}", getMethodSignatureString(invocation),
                        e.getMessage());
                }
            }

            Class<? extends PlisToException> targetExceptionClass =
                this.exceptionMappingSource.getToExceptionClass(remoteBeanMethod, e.getClass());
            if (targetExceptionClass == null) {
                targetExceptionClass =
                    this.exceptionMappingSource.getGenericTechnicalToException(remoteBeanMethod);
                LOG.warn(EreignisSchluessel.KEIN_EXCEPTION_MAPPING_DEFINIERT,
                    "Für die Serviceoperation {} ist kein Exception-Mapping für Exceptionklasse {} definiert. Benutze stattdessen technische TO-Exception {}",
                    getMethodSignatureString(invocation), e.getClass(), targetExceptionClass.getName());
            }
            PlisToException toException = PlisExceptionMapper.mapException(e, targetExceptionClass);
            throw toException;
        } catch (PlisTechnicalRuntimeException e) {
            LOG.error("Fehler in der Serviceoperation {}", e, getMethodSignatureString(invocation));
            PlisTechnicalToException toException = PlisExceptionMapper.mapException(e,
                this.exceptionMappingSource.getGenericTechnicalToException(remoteBeanMethod));
            throw toException;
        } catch (Throwable t) {
            // In seltenen Fällen trat bei Lasttests ein NoClassDefFound-Fehler beim Erzeugen der
            // RuntimeException auf. Da dabei der ursprüngliche Fehler verloren ging, wird hier nochmal
            // gecatcht.
            PlisTechnicalRuntimeException plisRuntimeException = null;
            try {

                // Die gefangene Exception wird in eine generische PLIS-RuntimeException gewrapped, damit die
                // Ausnahme-ID und Unique-ID, die zum Client übermittelt werden, auch im Server-Log stehen.
                plisRuntimeException = this.appTechnicalRuntimeExceptionCon.newInstance(
                    this.ausnahmeIdErmittler.ermittleAusnahmeId(t), t,
                    new String[] { ObjectUtils.defaultIfNull(t.getMessage(), "<Kein Fehlertext>") });
                LOG.error("Fehler in der Serviceoperation ", plisRuntimeException,
                    getMethodSignatureString(invocation));

            } catch (Throwable t2) {
                // Loggen des Sonderfalls Fehler in Fehlerbehandlung
                LOG.error(EreignisSchluessel.FEHLER_FEHLERBEHANDLUNG, "Fehler bei der Fehlerbehandlung", t2);
                LOG.error(EreignisSchluessel.FEHLER_FEHLERBEHANDLUNG, "Usprünglicher Fehler war {}", t,
                    t.getMessage());
            }

            PlisTechnicalToException toException = PlisExceptionMapper.mapException(plisRuntimeException,
                this.exceptionMappingSource.getGenericTechnicalToException(remoteBeanMethod));
            throw toException;

        }
    }

    /**
     * Erstellt den Signatur-String des gegebenen Aufrufs.
     *
     * @param invocation
     *            der Methodenaufruf
     *
     * @return der Signatur-String
     */
    protected String getMethodSignatureString(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        return getMethodSignatureString(method);
    }

    /**
     * Erstellt den Signatur-String des gegebenen Aufrufs.
     *
     * @param method
     *            die gerufene Methode
     *
     * @return der Signatur-String
     */
    protected String getMethodSignatureString(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateConfiguration(Class<?> remoteBeanInterface, Object target) {

        Class<?> targetClass = AopUtils.getTargetClass(target);

        for (Method remoteBeanMethod : remoteBeanInterface.getMethods()) {
            Class<? extends PlisTechnicalToException> genericTechnicalToExceptionClass =
                this.exceptionMappingSource.getGenericTechnicalToException(remoteBeanMethod);
            if (genericTechnicalToExceptionClass == null) {
                throw new IllegalStateException(
                    "Fehler in der statischen Konfiguration der Exception-Fassade für "
                        + remoteBeanInterface.getName() + ": Keine generische TO-Exception definiert");
            }
            if (!ArrayUtils.contains(remoteBeanMethod.getExceptionTypes(),
                genericTechnicalToExceptionClass)) {
                throw new IllegalStateException(
                    "Fehler in der statischen Konfiguration der Exception-Fassade für "
                        + getMethodSignatureString(remoteBeanMethod) + ": Die generische TO-Exception "
                        + genericTechnicalToExceptionClass.getSimpleName()
                        + " ist nicht im RemoteBean-Interface deklariert");
            }

            Method coreMethod = this.methodMappingSource.getTargetMethod(remoteBeanMethod, targetClass);

            for (Class<?> exceptionClass : coreMethod.getExceptionTypes()) {
                if (PlisException.class.isAssignableFrom(exceptionClass)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends PlisException> plisExceptionClass =
                        (Class<? extends PlisException>) exceptionClass;
                    Class<? extends PlisToException> plisToExceptionClass =
                        this.exceptionMappingSource.getToExceptionClass(remoteBeanMethod, plisExceptionClass);
                    if (plisToExceptionClass == null) {
                        throw new IllegalStateException(
                            "Fehler in der statischen Konfiguration der Exception-Fassade für "
                                + getMethodSignatureString(remoteBeanMethod)
                                + ": Keine TO-Exception für AWK-Exception "
                                + plisExceptionClass.getSimpleName() + " definiert");
                    }
                    if (!ArrayUtils.contains(remoteBeanMethod.getExceptionTypes(), plisToExceptionClass)) {
                        throw new IllegalStateException(
                            "Fehler in der statischen Konfiguration der Exception-Fassade für "
                                + getMethodSignatureString(remoteBeanMethod) + ": Die TO-Exception "
                                + plisToExceptionClass.getSimpleName()
                                + " ist nicht im RemoteBean-Interface deklariert");
                    }
                }
            }
        }
    }
}
