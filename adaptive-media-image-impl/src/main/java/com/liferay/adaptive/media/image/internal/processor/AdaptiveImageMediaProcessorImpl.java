/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.adaptive.media.image.internal.processor;

import com.liferay.adaptive.media.image.internal.configuration.AdaptiveImageConfiguration;
import com.liferay.adaptive.media.image.internal.configuration.AdaptiveImagePropertyMapping;
import com.liferay.adaptive.media.image.internal.configuration.AdaptiveImageVariantConfiguration;
import com.liferay.adaptive.media.image.internal.image.ImageProcessor;
import com.liferay.adaptive.media.image.internal.image.ImageStorage;
import com.liferay.adaptive.media.image.internal.source.AdaptiveImageMediaQueryBuilderImpl;
import com.liferay.adaptive.media.image.processor.AdaptiveImageMediaProcessor;
import com.liferay.adaptive.media.image.source.AdaptiveImageMediaQueryBuilder;
import com.liferay.adaptive.media.image.source.AdaptiveImageMediaSource;
import com.liferay.adaptive.media.processor.Media;
import com.liferay.adaptive.media.processor.MediaProcessor;
import com.liferay.adaptive.media.processor.MediaProcessorRuntimeException;
import com.liferay.adaptive.media.processor.MediaProperty;
import com.liferay.adaptive.media.source.MediaQuery;
import com.liferay.portal.kernel.repository.model.FileVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import java.net.URI;
import java.net.URLEncoder;

import java.nio.charset.StandardCharsets;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Adolfo Pérez
 */
@Component(
	immediate = true,
	property = "model.class.name=com.liferay.portal.kernel.repository.model.FileVersion",
	service = {
		AdaptiveImageMediaProcessor.class, AdaptiveImageMediaSource.class,
		MediaProcessor.class
	}
)
public final class AdaptiveImageMediaProcessorImpl
	implements AdaptiveImageMediaProcessor, AdaptiveImageMediaSource {

	@Override
	public void cleanUp(FileVersion fileVersion) {
		if (!_imageProcessor.isMimeTypeSupported(fileVersion.getMimeType())) {
			return;
		}

		_imageStorage.delete(fileVersion);
	}

	@Override
	public Stream<Media<AdaptiveImageMediaProcessor>> getMedia(
		Function<AdaptiveImageMediaQueryBuilder,
		MediaQuery<FileVersion, AdaptiveImageMediaProcessor>>
			queryBuilderFunction) {

		AdaptiveImageMediaQueryBuilderImpl adaptiveImageMediaQueryBuilder =
			new AdaptiveImageMediaQueryBuilderImpl();

		queryBuilderFunction.apply(adaptiveImageMediaQueryBuilder);

		FileVersion fileVersion =
			adaptiveImageMediaQueryBuilder.getFileVersion();

		if (!_imageProcessor.isMimeTypeSupported(fileVersion.getMimeType())) {
			return Stream.empty();
		}

		long companyId = fileVersion.getCompanyId();

		Collection<AdaptiveImageVariantConfiguration>
			adaptiveImageVariantConfigurations =
				_adaptiveImageConfiguration.
					getAdaptiveImageVariantConfigurations(companyId);

		return adaptiveImageVariantConfigurations.stream().
			map(
				adaptiveImageVariantConfiguration ->
					_createMedia(
						fileVersion, adaptiveImageVariantConfiguration)).
			sorted(
				_buildComparator(
					adaptiveImageMediaQueryBuilder.getMediaProperties()));
	}

	@Override
	public void process(FileVersion fileVersion) {
		if (!_imageProcessor.isMimeTypeSupported(fileVersion.getMimeType())) {
			return;
		}

		long companyId = fileVersion.getCompanyId();

		Iterable<AdaptiveImageVariantConfiguration>
			adaptiveImageVariantConfigurations =
				_adaptiveImageConfiguration.
					getAdaptiveImageVariantConfigurations(companyId);

		for (AdaptiveImageVariantConfiguration
				adaptiveImageVariantConfiguration :
					adaptiveImageVariantConfigurations) {

			try (InputStream inputStream = _imageProcessor.process(
					fileVersion, adaptiveImageVariantConfiguration)) {

				_imageStorage.save(
					fileVersion, adaptiveImageVariantConfiguration,
					inputStream);
			}
			catch (IOException ioe) {
				throw new MediaProcessorRuntimeException.IOException(ioe);
			}
		}
	}

	@Reference(unbind = "-")
	public void setAdaptiveImageConfiguration(
		AdaptiveImageConfiguration adaptiveImageConfiguration) {

		_adaptiveImageConfiguration = adaptiveImageConfiguration;
	}

	@Reference(unbind = "-")
	public void setImageProcessor(ImageProcessor imageProcessor) {
		_imageProcessor = imageProcessor;
	}

	@Reference(unbind = "-")
	public void setImageStorage(ImageStorage imageStorage) {
		_imageStorage = imageStorage;
	}

	private Comparator<Media<AdaptiveImageMediaProcessor>> _buildComparator(
		Map<MediaProperty<AdaptiveImageMediaProcessor, ?>, ?> properties) {

		return (media1, media2) -> {
			for (Map.Entry<MediaProperty<AdaptiveImageMediaProcessor, ?>, ?>
					entry : properties.entrySet()) {

				MediaProperty<AdaptiveImageMediaProcessor, Object>
					mediaProperty =
						(MediaProperty<AdaptiveImageMediaProcessor, Object>)
							entry.getKey();

				Object requestedValue = entry.getValue();

				if (requestedValue == null) {
					continue;
				}

				Optional<?> value1Optional = media1.getPropertyValue(
					mediaProperty);

				Optional<Integer> value1Distance = value1Optional.map(
					value1 -> mediaProperty.distance(value1, requestedValue));

				Optional<?> value2Optional = media2.getPropertyValue(
					mediaProperty);

				Optional<Integer> value2Distance = value2Optional.map(
					value2 -> mediaProperty.distance(value2, requestedValue));

				Optional<Integer> resultOptional = value1Distance.flatMap(
					value1 -> value2Distance.map(value2 -> value1 - value2));

				int result = resultOptional.orElse(0);

				if (result != 0) {
					return result;
				}
			}

			return 0;
		};
	}

	private URI _buildRelativeURI(
		FileVersion fileVersion,
		AdaptiveImageVariantConfiguration adaptiveImageVariantConfiguration) {

		String relativePath = String.format(
			"/adaptive/%d/%d/%d/%d/%d/%s/%s", fileVersion.getCompanyId(),
			fileVersion.getGroupId(), fileVersion.getRepositoryId(),
			fileVersion.getFileEntryId(), fileVersion.getFileVersionId(),
			adaptiveImageVariantConfiguration.getUUID(),
			_encode(fileVersion.getFileName()));

		return URI.create(relativePath);
	}

	private Media<AdaptiveImageMediaProcessor> _createMedia(
		FileVersion fileVersion,
		AdaptiveImageVariantConfiguration adaptiveImageVariantConfiguration) {

		AdaptiveImagePropertyMapping adaptiveImagePropertyMapping =
			AdaptiveImagePropertyMapping.fromProperties(
				adaptiveImageVariantConfiguration.getProperties());

		return new AdaptiveImageMedia(
			() ->
				_imageStorage.getContentStream(
					fileVersion, adaptiveImageVariantConfiguration),
			adaptiveImagePropertyMapping,
			_buildRelativeURI(fileVersion, adaptiveImageVariantConfiguration));
	}

	private String _encode(String s) {
		try {
			return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
		}
		catch (UnsupportedEncodingException uee) {
			throw new MediaProcessorRuntimeException.
				UnsupportedEncodingException(uee);
		}
	}

	private AdaptiveImageConfiguration _adaptiveImageConfiguration;
	private ImageProcessor _imageProcessor;
	private ImageStorage _imageStorage;

}