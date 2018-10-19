package de.metas.attachments;

import static org.adempiere.model.InterfaceWrapperHelper.deleteRecord;
import static org.adempiere.model.InterfaceWrapperHelper.load;
import static org.adempiere.model.InterfaceWrapperHelper.loadOutOfTrx;
import static org.adempiere.model.InterfaceWrapperHelper.newInstance;
import static org.adempiere.model.InterfaceWrapperHelper.saveRecord;

import lombok.NonNull;

import javax.annotation.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.util.lang.ITableRecordReference;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.model.I_AD_AttachmentEntry;
import org.compiere.model.I_AD_Attachment_MultiRef;
import org.springframework.stereotype.Repository;

import com.google.common.collect.ImmutableList;

import de.metas.attachments.AttachmentEntry.AttachmentEntryBuilder;
import de.metas.util.Services;
import de.metas.util.collections.CollectionUtils;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2018 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@Repository
public class AttachmentEntryRepository
{

	private final AttachmentEntryFactory attachmentEntryFactory;

	public AttachmentEntryRepository(@NonNull final AttachmentEntryFactory attachmentEntryFactory)
	{
		this.attachmentEntryFactory = attachmentEntryFactory;
	}

	public AttachmentEntry getById(@NonNull final AttachmentEntryId id)
	{
		final I_AD_AttachmentEntry attachmentEntryRecord = loadOutOfTrx(id, I_AD_AttachmentEntry.class);
		return forRecord(attachmentEntryRecord);
	}

	public List<AttachmentEntry> getByReferencedRecord(@NonNull final ITableRecordReference referencedRecord)
	{
		final IQueryBL queryBL = Services.get(IQueryBL.class);

		final List<I_AD_Attachment_MultiRef> multiRefRecords = queryBL
				.createQueryBuilder(I_AD_Attachment_MultiRef.class)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_AD_Attachment_MultiRef.COLUMN_Record_ID, referencedRecord.getRecord_ID())
				.addEqualsFilter(I_AD_Attachment_MultiRef.COLUMN_AD_Table_ID, referencedRecord.getAD_Table_ID())
				.create()
				.list();

		final List<Integer> attachmentEntryIds = CollectionUtils.extractDistinctElements(multiRefRecords, I_AD_Attachment_MultiRef::getAD_AttachmentEntry_ID);

		final List<I_AD_AttachmentEntry> attachmentEntryRecords = queryBL
				.createQueryBuilder(I_AD_AttachmentEntry.class)
				.addOnlyActiveRecordsFilter()
				.addInArrayFilter(I_AD_AttachmentEntry.COLUMN_AD_AttachmentEntry_ID, attachmentEntryIds)
				.create()
				.list();

		final ImmutableList.Builder<AttachmentEntry> result = ImmutableList.builder();
		for (final I_AD_AttachmentEntry attachmentEntryRecord : attachmentEntryRecords)
		{
			final AttachmentEntry attachmentEntry = forRecord(attachmentEntryRecord);
			result.add(attachmentEntry);
		}

		return result.build();

	}

	private AttachmentEntry forRecord(@NonNull final I_AD_AttachmentEntry attachmentEntryRecord)
	{
		final AttachmentEntryId attachmentEntryId = AttachmentEntryId.ofRepoIdOrNull(attachmentEntryRecord.getAD_AttachmentEntry_ID());
		final ImmutableList<I_AD_Attachment_MultiRef> attachmentMultiRefRecords = retrieveAttachmentMultiRefs(attachmentEntryId);

		return forRecord(attachmentEntryRecord, attachmentMultiRefRecords);
	}

	private AttachmentEntry forRecord(
			@NonNull final I_AD_AttachmentEntry attachmentEntryRecord,
			@NonNull final ImmutableList<I_AD_Attachment_MultiRef> attachmentMultiRefRecords)
	{
		final AttachmentEntry attachmentEntry = attachmentEntryFactory.toAttachmentEntry(attachmentEntryRecord);

		final AttachmentEntryBuilder builder = attachmentEntry.toBuilder();
		for (final I_AD_Attachment_MultiRef attachmentMultiRefRecord : attachmentMultiRefRecords)
		{
			final TableRecordReference referencedRecord = TableRecordReference.ofReferenced(attachmentMultiRefRecord);
			builder.linkedRecord(referencedRecord);
		}
		return builder.build();
	}

	public ImmutableList<AttachmentEntry> saveAll(@NonNull final Collection<AttachmentEntry> attachmentEntries)
	{
		final ImmutableList.Builder<AttachmentEntry> result = ImmutableList.builder();
		for (final AttachmentEntry attachmentEntry : attachmentEntries)
		{
			result.add(save(attachmentEntry));
		}
		return result.build();
	}

	public AttachmentEntry save(@NonNull final AttachmentEntry attachmentEntry)
	{
		final I_AD_AttachmentEntry attachmentEntryRecord;
		final boolean attachmentEntryRecordAlreadyExisted = attachmentEntry.getId() != null;

		if (attachmentEntryRecordAlreadyExisted)
		{
			attachmentEntryRecord = load(attachmentEntry.getId(), I_AD_AttachmentEntry.class);
		}
		else
		{
			attachmentEntryRecord = newInstance(I_AD_AttachmentEntry.class);
		}

		attachmentEntryFactory.syncToRecord(attachmentEntry, attachmentEntryRecord);

		saveRecord(attachmentEntryRecord);

		syncLinkedRecords(attachmentEntry, attachmentEntryRecord);


		return attachmentEntry
				.toBuilder()
				.id(AttachmentEntryId.ofRepoId(attachmentEntryRecord.getAD_AttachmentEntry_ID()))
				.build();
	}

	private void syncLinkedRecords(
			@NonNull final AttachmentEntry attachmentEntry,
			@NonNull final I_AD_AttachmentEntry attachmententryRecord)
	{
		final Set<ITableRecordReference> attachmentReferences = new HashSet<>(attachmentEntry.getLinkedRecords());

		// delete superflous
		if (attachmentEntry.getId() != null)
		{
			final List<I_AD_Attachment_MultiRef> attachmentMultiRefRecords = retrieveAttachmentMultiRefs(attachmentEntry.getId());
			for (final I_AD_Attachment_MultiRef attachmentMultiRefRecord : attachmentMultiRefRecords)
			{
				final TableRecordReference recordReference = TableRecordReference.ofReferenced(attachmentMultiRefRecord);
				final boolean recordReferenceWasIncluded = attachmentReferences.remove(recordReference);
				if (!recordReferenceWasIncluded)
				{
					deleteRecord(attachmentMultiRefRecord);
				}
			}
		}

		// create missing
		// i.e. iterate the references that did not yet have an I_AD_Attachment_MultiRef record
		for (final ITableRecordReference attachmentReference : attachmentReferences)
		{
			final I_AD_Attachment_MultiRef attachmentMultiRefRecord = newInstance(I_AD_Attachment_MultiRef.class);
			attachmentMultiRefRecord.setAD_AttachmentEntry(attachmententryRecord);
			attachmentMultiRefRecord.setAD_Table_ID(attachmentReference.getAD_Table_ID());
			attachmentMultiRefRecord.setRecord_ID(attachmentReference.getRecord_ID());
			saveRecord(attachmentMultiRefRecord);
		}
	}

	private ImmutableList<I_AD_Attachment_MultiRef> retrieveAttachmentMultiRefs(
			@Nullable final AttachmentEntryId attachmentEntryId)
	{
		if (attachmentEntryId == null)
		{
			return ImmutableList.of();
		}

		return Services.get(IQueryBL.class)
				.createQueryBuilder(I_AD_Attachment_MultiRef.class)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_AD_Attachment_MultiRef.COLUMN_AD_AttachmentEntry_ID, attachmentEntryId)
				.create()
				.listImmutable(I_AD_Attachment_MultiRef.class);
	}

	private void deleteAllAttachmentMultiRefs(@NonNull final AttachmentEntryId attachmententryId)
	{
		Services.get(IQueryBL.class)
				.createQueryBuilder(I_AD_Attachment_MultiRef.class)
				.addEqualsFilter(I_AD_Attachment_MultiRef.COLUMN_AD_AttachmentEntry_ID, attachmententryId)
				.create()
				.delete();
	}

	public void delete(@NonNull final AttachmentEntry attachmentEntry)
	{
		deleteAllAttachmentMultiRefs(attachmentEntry.getId());

		Services.get(IQueryBL.class).createQueryBuilder(I_AD_AttachmentEntry.class)
				.addEqualsFilter(I_AD_AttachmentEntry.COLUMN_AD_AttachmentEntry_ID, attachmentEntry.getId())
				.create()
				.delete();
	}
}
