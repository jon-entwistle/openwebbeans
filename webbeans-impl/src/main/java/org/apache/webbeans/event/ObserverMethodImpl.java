/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.webbeans.event;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.enterprise.context.ContextNotActiveException;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.Context;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.event.Reception;
import javax.enterprise.event.TransactionPhase;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.ObserverMethod;

import org.apache.webbeans.annotation.DefaultLiteral;
import org.apache.webbeans.component.AbstractOwbBean;
import org.apache.webbeans.component.AbstractInjectionTargetBean;
import org.apache.webbeans.component.InjectionTargetBean;
import org.apache.webbeans.container.BeanManagerImpl;
import org.apache.webbeans.container.InjectionResolver;
import org.apache.webbeans.exception.WebBeansException;
import org.apache.webbeans.inject.impl.InjectionPointFactory;
import org.apache.webbeans.logger.WebBeansLogger;
import org.apache.webbeans.util.AnnotationUtil;
import org.apache.webbeans.util.SecurityUtil;
import org.apache.webbeans.util.WebBeansUtil;

/**
 * Defines observers that are declared in observer methods.
 * <p>
 * Example:
 * <pre>
 *  public class X {
 *      
 *      public void afterLoggedIn(@Observes @Current LoggedInEvent event)
 *      {
 *          .....
 *      }
 *  }
 * </pre>
 * Above class X instance observes for the event with type <code>LoggedInEvent</code>
 * and event qualifier is <code>Current</code>. Whenever event is fired, its {@link Observer#notify()}
 * method is called.
 * </p>
 * 
 * @version $Rev$ $Date$
 *
 * @param <T> event type
 */
public class ObserverMethodImpl<T> implements ObserverMethod<T>
{
    /**Logger instance*/
    private static final WebBeansLogger logger = WebBeansLogger.getLogger(ObserverMethodImpl.class);

    /**Observer owner bean that defines observer method*/
    private final InjectionTargetBean<?> bean;

    /**Event observer method*/
    private final Method observerMethod;

    /**Using existing bean instance or not*/
    private final boolean ifExist;
    
    /** the observed qualifiers */
    private final Set<Annotation> observedQualifiers;

    /** the type of the observed event */
    private final Type observedEventType;
    
    /** the transaction phase */
    private final TransactionPhase phase;
    
    private static class ObserverParams
    {
        private Bean<Object> bean;
        
        private Object instance;
        
        private CreationalContext<Object> creational;
        
        private boolean isBean = false;
    }
    
    /**
     * Creates a new bean observer instance.
     * 
     * @param bean owner
     * @param observerMethod method
     * @param ifExist if exist parameter
     * @param type transaction type
     */
    public ObserverMethodImpl(InjectionTargetBean<?> bean, Method observerMethod, boolean ifExist)
    {
        this.bean = bean;
        this.observerMethod = observerMethod;
        this.ifExist = ifExist;
        
        Annotation[] qualifiers = AnnotationUtil.getMethodFirstParameterQualifierWithGivenAnnotation(observerMethod, Observes.class);
        AnnotationUtil.checkQualifierConditions(qualifiers);
        this.observedQualifiers = new HashSet<Annotation>(qualifiers.length);
        
        for (Annotation qualifier : qualifiers)
        {
            observedQualifiers.add(qualifier);
        }
        
        this.observedEventType = AnnotationUtil.getTypeOfParameterWithGivenAnnotation(observerMethod, Observes.class);
        
        this.phase = EventUtil.getObserverMethodTransactionType(observerMethod);
    }

    /**
     * used if the qualifiers and event type are already known, e.g. from the XML.
     * @param bean
     * @param observerMethod
     * @param ifExist
     * @param observedQualifiers
     * @param observedEventType
     */
    protected ObserverMethodImpl(InjectionTargetBean<?> bean, Method observerMethod, boolean ifExist,
                                 Annotation[] qualifiers, Type observedEventType)
    {
        this.bean = bean;
        this.observerMethod = observerMethod;
        this.ifExist = ifExist;
        this.observedQualifiers = new HashSet<Annotation>(qualifiers.length);
        for (Annotation qualifier : qualifiers)
        {
            observedQualifiers.add(qualifier);
        }
        this.observedEventType = observedEventType;
        this.phase = EventUtil.getObserverMethodTransactionType(observerMethod); //X TODO might be overriden via XML?

    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    public void notify(T event)
    {
        logger.trace("Notifying with event payload : ", new Object[]{event.toString()});
        
        AbstractOwbBean<Object> baseComponent = (AbstractOwbBean<Object>) bean;
        AbstractOwbBean<Object> specializedComponent = null;
        Object object = null;
        
        CreationalContext<Object> creationalContext = null;
        List<ObserverParams> methodArgsMap = getMethodArguments(event);
        ObserverParams[] obargs = null;
        try
        {
            if (!this.observerMethod.isAccessible())
            {
                SecurityUtil.doPrivilegedSetAccessible(observerMethod, true);
            }
            
            obargs = new ObserverParams[methodArgsMap.size()];
            obargs = methodArgsMap.toArray(obargs);
            Object[] args = new Object[obargs.length];
            int i = 0;
            for(ObserverParams param : obargs)
            {
                args[i++] = param.instance;
            }
            
            //Static or not
            if (Modifier.isStatic(this.observerMethod.getModifiers()))
            {
                //Invoke Method
                this.observerMethod.invoke(object, args);
            }
            else
            {
                BeanManagerImpl manager = BeanManagerImpl.getManager();
                specializedComponent = (AbstractOwbBean<Object>)WebBeansUtil.getMostSpecializedBean(manager, baseComponent);        
                Context context = null;
                try
                {
                    context = manager.getContext(specializedComponent.getScope());
                }
                catch (ContextNotActiveException cnae)
                {
                    // this may happen if we try to e.g. send an event to a @ConversationScoped bean from a ServletListener
                    logger.info("cannot send event to bean in non active context: " + bean.toString());
                    return;
                }
                
                creationalContext = manager.createCreationalContext(specializedComponent);
                
                // on Reception.IF_EXISTS: ignore this bean if a the contextual instance doesn't already exist
                if (ifExist && context.get(specializedComponent) == null) 
                {
                    return;
                }
                
                // on Reception.ALWAYS we must get a contextual reference if we didn't find the contextual instance
                object = manager.getReference(specializedComponent, specializedComponent.getBeanClass(), creationalContext);
                
                if (object != null)
                {
                    //Invoke Method
                    this.observerMethod.invoke(object, args);
                }
            }                        
        }
        catch (Exception e)
        {
                throw new WebBeansException(e);
        }
        finally
        {
            //Destory bean instance
            if (baseComponent.getScope().equals(Dependent.class) && object != null)
            {
                baseComponent.destroy(object,creationalContext);
            }
            
            //Destroy observer method dependent instances
            if(methodArgsMap != null)
            {
                for(ObserverParams param : obargs)
                {
                    if(param.isBean && param.bean.getScope().equals(Dependent.class))
                    {
                        param.bean.destroy(param.instance, param.creational);
                    }
                }
            }
        }

    }

    /**
     * Returns list of observer method parameters.
     * 
     * @param event event instance
     * @return list of observer method parameters
     */
    protected List<ObserverParams> getMethodArguments(Object event)
    {
        Type[] types = this.observerMethod.getGenericParameterTypes();

        Annotation[][] annots = this.observerMethod.getParameterAnnotations();

        List<ObserverParams> list = new ArrayList<ObserverParams>();

        BeanManagerImpl manager = BeanManagerImpl.getManager();
        ObserverParams param = null;
        if (types.length > 0)
        {
            int i = 0;
            for (Type type : types)
            {
                Annotation[] annot = annots[i];

                boolean observesAnnotation = false;

                if (annot.length == 0)
                {
                    annot = new Annotation[1];
                    annot[0] = new DefaultLiteral();
                }
                else
                {
                    for (Annotation observersAnnot : annot)
                    {
                        if (observersAnnot.annotationType().equals(Observes.class))
                        {
                            param = new ObserverParams();
                            param.instance = event;
                            list.add(param); 
                            observesAnnotation = true;
                            break;
                        }
                    }
                }

                if (!observesAnnotation)
                {
                    //Get parameter annotations
                    Annotation[] bindingTypes = AnnotationUtil.getQualifierAnnotations(annot);

                    if (bindingTypes.length > 0)
                    {
                        InjectionPoint point = InjectionPointFactory.getPartialInjectionPoint(null, type, null, null, bindingTypes);
                        @SuppressWarnings("unchecked")
                        Bean<Object> bean = (Bean<Object>)InjectionResolver.getInstance().getInjectionPointBean(point);
                        CreationalContext<Object> creational = manager.createCreationalContext(bean);
                        Object instance = manager.getInstance(bean, creational); 
                        
                        param = new ObserverParams();
                        param.isBean = true;
                        param.creational = creational;
                        param.instance = instance;
                        param.bean = bean;
                        list.add(param);
                    }
                    else
                    {
                        param = new ObserverParams();
                        list.add(param);
                    }
                }
                
                i++;
            }
        }

        return list;
    }

    /**
     * Returns observer owner bean.
     * 
     * @return the bean
     */
    @SuppressWarnings("unchecked")
    public Class<?> getBeanClass()
    {
        AbstractInjectionTargetBean<T> abs = (AbstractInjectionTargetBean<T>)this.bean;
        return abs.getBeanClass();
    }

    /** 
     * {@inheritDoc}
     */
    public Set<Annotation> getObservedQualifiers() 
    {
        return observedQualifiers;
    }
    
    /** 
     * {@inheritDoc}
     */
    public Type getObservedType() 
    {
        return observedEventType;
    }

    /** 
     * {@inheritDoc}
     */
    public Reception getReception() 
    {
        return ifExist ? Reception.IF_EXISTS : Reception.ALWAYS;
    }

    public TransactionPhase getTransactionPhase()
    {
        return phase;
    }
    
    public Method getObserverMethod()
    {
        return this.observerMethod;
    }

}
